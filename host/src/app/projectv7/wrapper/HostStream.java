package app.projectv7.wrapper;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded asynchronous transport for guest URLConnection/InputStream calls. */
public final class HostStream {
    private static final AtomicInteger ids = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Transfer> active = new ConcurrentHashMap<>();
    private static final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "PV7-Stream"); t.setDaemon(true); return t;
    });
    private static final class Transfer {
        volatile int status = -2;
        volatile boolean done, closed;
        volatile HttpURLConnection connection;
        volatile String error = "";
        volatile Map<String,List<String>> headers = Collections.emptyMap();
        final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(8);
        byte[] current; int offset;
        Future<?> task;
    }
    public static synchronized int open(String url, String method, String[] headers, int connectMs, int readMs) {
        if (active.size() >= 32) return -1;
        final Transfer t = new Transfer(); final int id = ids.getAndIncrement(); active.put(id,t);
        t.task = workers.submit(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection(); t.connection=c;
                c.setConnectTimeout(connectMs); c.setReadTimeout(readMs); c.setUseCaches(false);
                c.setInstanceFollowRedirects(true); c.setRequestMethod(method);
                for (int i=0;i+1<headers.length;i+=2) c.setRequestProperty(headers[i],headers[i+1]);
                int status=c.getResponseCode(); t.headers=c.getHeaderFields(); t.status=status;
                if (!"HEAD".equals(method)) {
                    InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
                    if (stream!=null) try (InputStream in=stream) {
                        byte[] buffer=new byte[32768]; int n;
                        while (!t.closed && (n=in.read(buffer))!=-1)
                            if(n>0) t.chunks.put(Arrays.copyOf(buffer,n));
                    }
                }
            } catch(Exception e) { t.error=e.toString(); if(t.status==-2)t.status=-1; }
            finally { t.done=true; if(t.connection!=null)t.connection.disconnect(); }
        });
        return id;
    }
    public static int status(int id) { Transfer t=active.get(id); return t==null?-1:t.status; }
    public static String header(int id,String key) {
        Transfer t=active.get(id); if(t==null)return null;
        for(Map.Entry<String,List<String>> e:t.headers.entrySet())
            if(e.getKey()!=null && e.getKey().equalsIgnoreCase(key))
                return e.getValue()==null||e.getValue().isEmpty()?null:e.getValue().get(0);
        return null;
    }
    // null means not ready; empty means EOF. No waiting on the render thread.
    public static byte[] read(int id,int capacity) {
        Transfer t=active.get(id); if(t==null)return new byte[0];
        synchronized(t) {
            if(t.current==null) { t.current=t.chunks.poll(); t.offset=0; }
            if(t.current==null)return t.done?new byte[0]:null;
            int n=Math.min(capacity,t.current.length-t.offset);
            byte[] result=Arrays.copyOfRange(t.current,t.offset,t.offset+n);t.offset+=n;
            if(t.offset==t.current.length)t.current=null;
            return result;
        }
    }
    public static String error(int id) { Transfer t=active.get(id);return t==null?"closed":t.error; }
    public static void close(int id) {
        Transfer t=active.remove(id); if(t==null)return;t.closed=true;
        if(t.task!=null)t.task.cancel(true);t.chunks.clear();
        // Disconnect on a worker; never block the game thread in socket teardown.
        if(t.connection!=null)workers.submit(() -> t.connection.disconnect());
    }
}
