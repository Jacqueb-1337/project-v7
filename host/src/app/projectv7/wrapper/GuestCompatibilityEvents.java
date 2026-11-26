package app.projectv7.wrapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared bounded supervisor event queue; engine adapters report verified outcomes. */
final class GuestCompatibilityEvents {
    static final class Event {
        final String backend,signature,detail;
        final boolean critical;
        int count=1;
        Event(String b,String s,String d,boolean c){backend=b;signature=s;detail=d;critical=c;}
        String key(){return backend+"|"+signature+"|"+critical;}
        String message(){return (critical?"Compatibility error":"Compatibility warning")+": "+signature+"\n"+detail;}
    }
    private static final int LIMIT=64;
    private static final long INTERVAL=5000;
    private static final Map<String,Event> events=new LinkedHashMap<>();
    private static final java.util.ArrayDeque<Event> pending=new java.util.ArrayDeque<>();
    private static long nextNotice;
    private GuestCompatibilityEvents(){}
    static synchronized void reset(){events.clear();pending.clear();nextNotice=0;}
    private static String clean(String value,int limit){
        if(value==null)return "";
        StringBuilder safe=new StringBuilder();
        for(int i=0;i<value.length()&&safe.length()<limit;i++){
            char c=value.charAt(i);
            safe.append(Character.isISOControl(c)?' ':c);
        }
        return safe.toString();
    }
    static synchronized Event report(String backend,String signature,String detail,boolean critical){
        Event e=new Event(clean(backend,32),clean(signature,300),clean(detail,300),critical);
        Event prior=events.get(e.key());
        if(prior!=null){if(prior.count<Integer.MAX_VALUE)prior.count++;return null;}
        if(events.size()>=LIMIT){
            if(!critical)return null;
            String evict=null;
            for(Event old:events.values())if(!old.critical){evict=old.key();break;}
            if(evict==null)return null;
            pending.remove(events.remove(evict));
        }
        events.put(e.key(),e);
        if(critical){pending.addFirst(e);nextNotice=0;}
        else pending.addLast(e);
        return e;
    }
    static synchronized Event poll(long now){
        if(pending.isEmpty()||now<nextNotice)return null;
        Event e=pending.removeFirst();nextNotice=now+INTERVAL;return e;
    }
    static String extractFault(String tail){
        if(tail==null)return null;
        String match=null;
        for(String line:tail.split("\r?\n")){
            if(!line.contains("COCOS_FAULT_DETAIL"))continue;
            int start=line.indexOf(" reason=");
            if(start>=0)match=clean(line.substring(start+8),300);
        }
        return match;
    }
}
