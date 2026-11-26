package app.projectv7.wrapper;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Host-side HTTPS transport for Unity 4.6's translated com.unity3d.player.WWW bridge. */
public final class HostHttp {
    private HostHttp() {}

    public static final class Result {
        public final byte[] body;
        public final String headers;
        public final String error;
        public final int status;
        public final int requestId;

        Result(byte[] body, String headers, String error, int status) {
            this(body, headers, error, status, 0);
        }
        Result(byte[] body, String headers, String error, int status, int requestId) {
            this.requestId = requestId;
            this.body = body;
            this.headers = headers;
            this.error = error;
            this.status = status;
        }
    }

    // Only the CNR module opts in. No guest references cross the worker boundary.
    // One FIFO worker preserves ordering of startup account mutations.
    public static final class AsyncSession {
        private static final int MAX_OUTSTANDING = 32;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PV7-CNR-HTTP");
            t.setDaemon(true);
            return t;
        });
        private final ArrayDeque<Result> completed = new ArrayDeque<>();
        private int outstanding;
        private boolean closed;
        public synchronized boolean enqueue(int requestId, String url, byte[] post) {
            if (closed || outstanding >= MAX_OUTSTANDING || url == null || url.isEmpty()) return false;
            ++outstanding;
            try {
                executor.execute(() -> {
                    Result r = fetch(url, post);
                    synchronized (this) {
                        if (!closed) completed.addLast(new Result(r.body, r.headers, r.error, r.status, requestId));
                    }
                });
            } catch (RejectedExecutionException ex) {
                --outstanding;
                return false;
            }
            return true;
        }
        public synchronized Result poll() {
            Result r = completed.pollFirst();
            if (r != null) --outstanding;
            return r;
        }
        public synchronized void close() {
            closed = true;
            completed.clear();
            outstanding = 0;
            executor.shutdownNow();
        }
    }

    public static Result fetch(String urlText, byte[] postData) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "UnityPlayer/4.6 ProjectV7");

            if (postData != null) {
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setFixedLengthStreamingMode(postData.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(postData);
                }
            }

            final int status = connection.getResponseCode();
            StringBuilder headers = new StringBuilder();
            Map<String, List<String>> fields = connection.getHeaderFields();
            if (fields != null) {
                for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) continue;
                    List<String> values = entry.getValue();
                    if (values == null || values.isEmpty()) {
                        headers.append(key).append(": \n\r");
                    } else {
                        for (String value : values) {
                            headers.append(key).append(": ").append(value == null ? "" : value).append("\n\r");
                        }
                    }
                }
            }
            if (connection.getContentLength() >= 0) {
                headers.append("content-length: ").append(connection.getContentLength()).append("\n\r");
            }
            if (connection.getContentType() != null) {
                headers.append("content-type: ").append(connection.getContentType()).append("\n\r");
            }

            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream(
                    Math.max(1024, Math.min(Math.max(connection.getContentLength(), 0), 1024 * 1024)));
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count > 0) body.write(buffer, 0, count);
                    }
                }
            }
            return new Result(body.toByteArray(), headers.toString(), null, status);
        } catch (Throwable error) {
            Log.e("PV7HOST", "PV7WWW HostHttp failed url=" + urlText, error);
            return new Result(new byte[0], "", error.toString(), -1);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
