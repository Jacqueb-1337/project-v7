package app.projectv7.wrapper;

import android.app.Activity;
import android.util.Log;
import android.view.Surface;
import android.view.MotionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Cocos backend for the common PV7 supervisor. No independent Activity or watchdog. */
public final class CocosGuestRuntime {
    private static final String TAG = "PV7Cocos";
    private static volatile Activity active;
    private static native int nativeRun(String guestPath, String logPath, Surface surface, int maxFrames);
    private static native void nativePrepare(boolean bestPark);
    private static native void nativeCancel();
    private static native void nativeSetPaused(boolean paused);
    private static native long nativeFrameCount();
    private static native long nativePresentCount();
    private static native void nativeQueueTouch(int phase, int pointer, float x, float y);
    private static boolean loaded;
    private CocosGuestRuntime() {}
    static synchronized void load() {
        if (!loaded) { System.loadLibrary("pv7cocosprobe"); loaded = true; }
    }
    static void prepare(Activity owner, String guestPackage, int guestVersion) throws java.io.IOException {
        active = owner;
        File shared = new File(owner.getFilesDir(), "GuestSharedStorage");
        for (String name : new String[]{"obb","data","cache","files"}) {
            File path = new File(shared,name);
            if (!path.isDirectory() && !path.mkdirs()) throw new java.io.IOException("Guest storage: "+name);
        }
        if (guestPackage == null || !guestPackage.matches("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+") || guestVersion <= 0)
            throw new java.io.IOException("Original guest package/version missing");
        File expansion = new File(new File(shared,"obb"),"main."+guestVersion+"."+guestPackage+".obb");
        expansionPath = expansion.getAbsolutePath();
        Log.i(TAG,"Original expansion exists="+expansion.isFile());
    }
    static void reset(boolean bestPark) { nativePrepare(bestPark); }
    static void stop() { if (loaded) nativeCancel(); }
    static void background(boolean paused) { if (loaded) nativeSetPaused(paused); }
    static long frames() { return loaded ? nativeFrameCount() : 0; }
    static long presents() { return loaded ? nativePresentCount() : 0; }
    public static void onGuestFirstFrame() {} // shared supervisor consumes native presentation counter
    static int run(File guest, File report, Surface surface) {
        try { return nativeRun(guest.getAbsolutePath(),report.getAbsolutePath(),surface,0); }
        finally { releaseGuestBackgroundMusic(); releaseGuestEffects(); active=null; }
    }
    static void touch(MotionEvent event,int width,int height) {
        if (width<=0 || height<=0) return;
        int action=event.getActionMasked(), phase;
        switch(action) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN: phase=0;break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP: phase=1;break;
            case MotionEvent.ACTION_MOVE: phase=2;break;
            case MotionEvent.ACTION_CANCEL: phase=3;break;
            default:return;
        }
        int first=phase>=2?0:event.getActionIndex(), last=phase>=2?event.getPointerCount():first+1;
        for(int i=first;i<last;i++) nativeQueueTouch(phase,event.getPointerId(i),
            Math.max(0,Math.min(480,event.getX(i)*480f/width)),
            Math.max(0,Math.min(320,event.getY(i)*320f/height)));
    }
    public static String getGuestLanguage() { return java.util.Locale.getDefault().getLanguage(); }
    /** Original Cocos2dxHelper.getDeviceModel() returns android.os.Build.MODEL. */
    public static String getGuestDeviceModel() { return android.os.Build.MODEL; }
    /** Original regularshow.isKindle checks Build.MANUFACTURER == Amazon. */
    public static boolean isGuestKindle() { return "Amazon".equals(android.os.Build.MANUFACTURER); }
    private static final Object effectsLock = new Object();
    private static android.media.SoundPool effectPool;
    private static final java.util.LinkedHashSet<Integer> effectStreams = new java.util.LinkedHashSet<>();
    private static final java.util.Map<String, Integer> effectSoundIds =
            new java.util.HashMap<>();
    private static final java.util.Map<Integer, Integer> effectLoaded =
            new java.util.HashMap<>();

    /** Source-compatible original Cocos2dxSound behavior through SoundPool. */
    public static int playGuestEffect(String path, boolean loop, float volume) {
        if (!Float.isFinite(volume)) return -1;
        final String asset;
        try {
            asset = resolveGuestAudioFile(path);
        } catch (java.io.IOException e) {
            Log.w(TAG, "Original guest effect missing: " + path, e);
            return -1; // Same as original preloadEffect failure.
        }
        synchronized (effectsLock) {
            if (effectPool == null) {
                effectPool = new android.media.SoundPool.Builder()
                        .setMaxStreams(16).build();
                effectPool.setOnLoadCompleteListener((pool, id, status) -> {
                    synchronized (effectsLock) {
                        if (pool != effectPool) return;
                        effectLoaded.put(id, status);
                        effectsLock.notifyAll();
                    }
                });
            }
            Integer id = effectSoundIds.get(path);
            if (id == null) {
                int loaded = effectPool.load(asset, 1);
                if (loaded <= 0) {
                    Log.w(TAG, "SoundPool rejected original asset " + path);
                    return -1;
                }
                id = loaded;
                effectSoundIds.put(path, id);
            }
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            while (!effectLoaded.containsKey(id)) {
                long remaining = deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    Log.w(TAG, "Timed out loading original Cocos sound " + path);
                    return -1;
                }
                try {
                    effectsLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (effectLoaded.get(id) != 0) return -1;
            float gain = Math.max(0f, Math.min(1f, volume));
            int stream = effectPool.play(id, gain, gain, 1, loop ? -1 : 0, 1f);
            if (stream != 0) {
                effectStreams.add(stream);
                if (effectStreams.size() > 256)
                    effectStreams.remove(effectStreams.iterator().next());
            }
            Log.i(TAG, "Original guest effect " + path + " stream=" + stream +
                    " looping=" + loop + " volume=" + gain);
            return stream == 0 ? -1 : stream;
        }
    }

    /** Cocos2dxHelper.stopEffect(int): stop the real stream ID returned by playEffect. */
    public static void stopGuestEffect(int streamId) {
        synchronized (effectsLock) {
            if (effectPool != null) effectPool.stop(streamId);
            effectStreams.remove(streamId);
        }
        Log.i(TAG, "Original guest effect stopped stream=" + streamId);
    }

    /** Stop all effect streams and reset the pool for subsequent effects. */
    public static void stopAllGuestEffects() {
        synchronized (effectsLock) {
            if (effectPool != null) for (int stream : effectStreams) effectPool.stop(stream);
            effectStreams.clear();
        }
        Log.i(TAG, "Original guest effects stopped");
    }

    private static void releaseGuestEffects() {
        synchronized (effectsLock) {
            if (effectPool != null) effectPool.release();
            effectPool = null;
            effectStreams.clear();
            effectSoundIds.clear();
            effectLoaded.clear();
        }
    }

    public static void pauseGuestEffect(int id) {
        synchronized (effectsLock) { if (effectPool != null) effectPool.pause(id); }
    }
    public static void resumeGuestEffect(int id) {
        synchronized (effectsLock) { if (effectPool != null) effectPool.resume(id); }
    }
    public static void pauseAllGuestEffects() {
        synchronized (effectsLock) { if (effectPool != null) effectPool.autoPause(); }
    }
    public static void resumeAllGuestEffects() {
        synchronized (effectsLock) {
            if (effectPool != null) for (int stream : effectStreams) effectPool.resume(stream);
        }
    }
    public static int getGuestDpi() {
        Activity owner = active;
        return owner == null ? -1 : (int)(owner.getResources().getDisplayMetrics().density * 160.0f);
    }

    private static final Object musicLock = new Object();
    private static android.media.MediaPlayer backgroundPlayer;
    private static String backgroundPath;
    private static float guestMusicVolume = 1.0f;
    private static boolean guestMusicPaused;

    public static void pauseGuestMusic() {
        synchronized (musicLock) {
            if (backgroundPlayer != null && backgroundPlayer.isPlaying()) {
                backgroundPlayer.pause(); guestMusicPaused = true;
            }
        }
    }
    public static void resumeGuestMusic() {
        synchronized (musicLock) {
            if (backgroundPlayer != null && guestMusicPaused) {
                backgroundPlayer.start(); guestMusicPaused = false;
            }
        }
    }
    public static void rewindGuestMusic() {
        synchronized (musicLock) {
            if (backgroundPlayer != null) {
                backgroundPlayer.seekTo(0); backgroundPlayer.start(); guestMusicPaused = false;
            }
        }
    }
    public static boolean isGuestMusicPlaying() {
        synchronized (musicLock) { return backgroundPlayer != null && backgroundPlayer.isPlaying(); }
    }

    /** Original Cocos2dxMusic volume behavior; also updates active playback. */
    public static void setGuestBackgroundMusicVolume(float volume) {
        if (!Float.isFinite(volume)) throw new IllegalArgumentException("Invalid guest volume");
        synchronized (musicLock) {
            guestMusicVolume = Math.max(0.0f, Math.min(1.0f, volume));
            if (backgroundPlayer != null)
                backgroundPlayer.setVolume(guestMusicVolume, guestMusicVolume);
        }
        Log.i(TAG, "Guest background music volume=" + guestMusicVolume);
    }

    /** Resolve only files actually packaged with this diagnostic's original guest. */
    public static String resolveGuestAudioFile(String requested) throws java.io.IOException {
        Activity owner = active;
        if (owner == null || requested == null || requested.isEmpty() ||
            requested.length() > 300 || requested.indexOf('\\') >= 0 ||
            requested.indexOf(':') >= 0 || requested.indexOf('\0') >= 0)
            throw new java.io.IOException("Invalid guest music path");
        if (requested.startsWith("/")) {
            // Absolute paths are permitted only under this app's private directories.
            File absolute = new File(requested).getCanonicalFile();
            String files = owner.getFilesDir().getCanonicalPath() + File.separator;
            String cache = owner.getCacheDir().getCanonicalPath() + File.separator;
            if (!(absolute.getPath().startsWith(files) ||
                  absolute.getPath().startsWith(cache)) || !absolute.isFile())
                throw new java.io.FileNotFoundException("Guest audio path outside private storage");
            return absolute.getAbsolutePath();
        }
        if (java.util.Arrays.asList(requested.split("/")).contains("..") ||
            requested.startsWith("./") || requested.startsWith("//"))
            throw new java.io.IOException("Invalid guest music traversal");
        File obb = new File(expansionPath);
        if (obb.isFile()) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(obb)) {
                java.util.zip.ZipEntry entry = zip.getEntry(requested);
                if (entry != null && !entry.isDirectory() &&
                    entry.getSize() <= 32L * 1024L * 1024L) {
                    // Extract to a unique, canonical app-private music path.
                    File root = new File(owner.getCacheDir(), "guest-audio").getCanonicalFile();
                    File dest = new File(root, requested).getCanonicalFile();
                    if (!dest.getPath().startsWith(root.getPath() + File.separator))
                        throw new java.io.IOException("Guest music path escape");
                    if (dest.isFile() && entry.getSize() >= 0 &&
                        dest.length() == entry.getSize()) return dest.getAbsolutePath();
                    if (!dest.getParentFile().isDirectory() && !dest.getParentFile().mkdirs())
                        throw new java.io.IOException("Could not create guest audio folder");
                    File temp = new File(dest.getParentFile(), dest.getName() + ".part");
                    long size = 0;
                    try (java.io.InputStream input = zip.getInputStream(entry);
                         FileOutputStream output = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            size += count;
                            if (size > 32L * 1024L * 1024L)
                                throw new java.io.IOException("Guest audio asset exceeds limit");
                            output.write(buffer, 0, count);
                        }
                    } catch (Exception e) {
                        temp.delete();
                        throw e;
                    }
                    if (dest.exists() && !dest.delete()) {
                        temp.delete();
                        throw new java.io.IOException("Unable to replace guest audio");
                    }
                    if (!temp.renameTo(dest))
                        throw new java.io.IOException("Unable to stage guest audio");
                    Log.i(TAG, "Guest music staged from original OBB: " + requested);
                    return dest.getAbsolutePath();
                }
            }
        }
        // Original Cocos2dxMusic falls back to APK AssetManager when OBB absent;
        // for this diagnostic, allow APK assets even if OBB is present but lacks a file.
        File root = new File(owner.getCacheDir(), "guest-audio-apk").getCanonicalFile();
        File dest = new File(root, requested).getCanonicalFile();
        if (!dest.getPath().startsWith(root.getPath() + File.separator))
            throw new java.io.IOException("Guest music APK path escape");
        if (!dest.getParentFile().isDirectory() && !dest.getParentFile().mkdirs())
            throw new java.io.IOException("Could not create guest APK audio folder");
        try (java.io.InputStream input = owner.getAssets().open(requested);
             FileOutputStream output = new FileOutputStream(dest)) {
            byte[] buffer = new byte[32768];
            int count;
            long size = 0;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > 32L * 1024L * 1024L)
                    throw new java.io.IOException("Guest APK music exceeds limit");
                output.write(buffer, 0, count);
            }
        }
        Log.i(TAG, "Guest music staged from original APK: " + requested);
        return dest.getAbsolutePath();
    }

    /** Real Android MediaPlayer bridge for original Cocos2dxHelper.playBackgroundMusic. */
    public static void playGuestBackgroundMusic(String requested, boolean loop)
            throws java.io.IOException {
        String filePath = resolveGuestAudioFile(requested);
        synchronized (musicLock) {
            if (backgroundPlayer != null) {
                backgroundPlayer.release();
                backgroundPlayer = null;
            }
            android.media.MediaPlayer player = new android.media.MediaPlayer();
            try {
                player.setDataSource(filePath);
                player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                player.setLooping(loop);
                player.prepare();
                player.setVolume(guestMusicVolume, guestMusicVolume);
                player.start();
                backgroundPlayer = player;
                guestMusicPaused = false;
                backgroundPath = requested;
            } catch (java.io.IOException | RuntimeException e) {
                player.release();
                throw e;
            }
        }
        Log.i(TAG, "Guest background music PLAYING from original asset: " +
                requested + " looping=" + loop);
    }

    /** Original Cocos2dxMusic.stopBackgroundMusic() stops active MediaPlayer. */
    public static void stopGuestBackgroundMusic() {
        synchronized (musicLock) {
            if (backgroundPlayer != null) {
                backgroundPlayer.stop();
                backgroundPlayer.release();
                backgroundPlayer = null;
            }
            backgroundPath = null;
        }
        Log.i(TAG, "Original guest background music stopped");
    }

    private static void releaseGuestBackgroundMusic() {
        synchronized (musicLock) {
            if (backgroundPlayer != null) {
                backgroundPlayer.release();
                backgroundPlayer = null;
            }
            backgroundPath = null;
        }
    }

    /** Mirrors the original Cocos2dxHelper.getBoolForKey() implementation. */
    public static boolean readCocosBoolean(String key, boolean fallback) {
        Activity owner = active;
        if (owner == null) throw new IllegalStateException("Activity context not initialized");
        return owner.getSharedPreferences("Cocos2dxPrefsFile", android.content.Context.MODE_PRIVATE)
                .getBoolean(key, fallback);
    }
    /** Mirrors original Cocos2dxHelper.setBoolForKey: synchronous commit. */
    public static void writeCocosBoolean(String key, boolean value) {
        Activity owner = active;
        if (owner == null) throw new IllegalStateException("Activity context not initialized");
        boolean saved = owner.getSharedPreferences("Cocos2dxPrefsFile", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
        if (!saved) Log.w(TAG, "Original Cocos boolean preference commit returned false");
    }

    /** Mirrors original setIntegerForKey with a synchronous commit. */
    public static void writeCocosInteger(String key, int value) {
        Activity owner = active;
        if (owner == null) throw new IllegalStateException("Activity not initialized");
        if (key == null) throw new IllegalArgumentException("Null guest preference key");
        if (!owner.getSharedPreferences("Cocos2dxPrefsFile", android.content.Context.MODE_PRIVATE)
                .edit().putInt(key, value).commit())
            Log.w(TAG, "Original guest integer preference commit failed");
        Log.i(TAG, "Original guest integer preference persisted key=" + key + " value=" + value);
    }

    public static int readCocosInteger(String key, int fallback) {
        Activity owner=active;
        if (owner==null) throw new IllegalStateException("Activity context not initialized");
        return owner.getSharedPreferences("Cocos2dxPrefsFile", android.content.Context.MODE_PRIVATE)
                .getInt(key, fallback);
    }

    public static String readCocosPreference(String key, String fallback) {
        Activity owner=active;
        if (owner==null) throw new IllegalStateException("Activity context not initialized");
        return owner.getSharedPreferences("Cocos2dxPrefsFile", android.content.Context.MODE_PRIVATE)
                .getString(key, fallback);
    }
    /** Mirrors original setStringForKey with a synchronous preference commit. */
    public static void writeCocosString(String key, String value) {
        Activity owner = active;
        if (owner == null) throw new IllegalStateException("Activity not initialized");
        if (key == null || value == null) throw new IllegalArgumentException("Null guest preference");
        boolean committed = owner.getSharedPreferences("Cocos2dxPrefsFile", android.content.Context.MODE_PRIVATE)
                .edit().putString(key, value).commit();
        if (!committed) Log.w(TAG, "Original guest string preference commit failed");
        Log.i(TAG, "Original guest string preference persisted key=" + key);
    }
    private static volatile long animationIntervalNanos = 16_666_667L;

    public static void setGuestAnimationInterval(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 10) {
            throw new IllegalArgumentException("Invalid Cocos frame interval: " + seconds);
        }
        animationIntervalNanos = (long)(1_000_000_000d * seconds);
        Log.i(TAG, "Original Cocos animation interval ns=" + animationIntervalNanos);
    }

    public static String getGuestWritablePath() { Activity owner=active; if(owner==null) throw new IllegalStateException("Activity not ready"); return new File(owner.getFilesDir(), "GuestSharedStorage/files").getAbsolutePath(); }
    public static String getGuestApkPath() {
        Activity owner = active;
        if (owner == null) throw new IllegalStateException("Activity not ready");
        return owner.getApplicationInfo().sourceDir;
    }
    public static String resolveGuestObbFile(String virtualPath) throws java.io.IOException {
        Activity owner=active;
        if(owner==null || virtualPath==null || !virtualPath.startsWith("/")) return null;
        final String relative=virtualPath.substring(1);
        if(!(relative.startsWith("sd/") || relative.startsWith("hd/") ||
             relative.startsWith("ipad3/") || relative.startsWith("sounds/")) ||
           relative.contains("\\") || relative.contains(":") ||
           java.util.Arrays.asList(relative.split("/")).contains("..")) return null;
        File expansion=new File(expansionPath);
        if(!expansion.isFile()) {
            GuestStorageDiagnostics.report(expansionPath,false,
                expansion.exists() ? "unreadable" : "missing",android.os.SystemClock.elapsedRealtime());
            return null;
        }
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(expansion)) {
            GuestStorageDiagnostics.report(expansionPath,true,"",android.os.SystemClock.elapsedRealtime());
            java.util.zip.ZipEntry entry=zip.getEntry(relative);
            String sourceName=relative;
            if(entry==null) sourceName=relative.substring(relative.indexOf('/')+1);
            if(entry!=null && (entry.isDirectory() || entry.getSize()>16*1024*1024L))
                return null;
            File root=new File(owner.getCacheDir(),"guest-resource-files").getCanonicalFile();
            File output=new File(root,relative).getCanonicalFile();
            if(!output.getPath().startsWith(root.getPath()+File.separator)) return null;
            if(output.isFile() && (entry==null ||
                (entry.getSize()>=0 && output.length()==entry.getSize())))
                return output.getAbsolutePath();
            File parent=output.getParentFile();
            if(!parent.isDirectory()&&!parent.mkdirs()) return null;
            InputStream input;
            try {
                input=entry!=null ? zip.getInputStream(entry) : owner.getAssets().open(sourceName);
            } catch(java.io.FileNotFoundException missing) {
                return null;
            }
            File temp=new File(parent,output.getName()+".part");
            long total=0;
            try(InputStream data=input;FileOutputStream stream=new FileOutputStream(temp)) {
                byte[] buffer=new byte[32768];
                int count;
                while((count=data.read(buffer))!=-1) {
                    total+=count;
                    if(total>16*1024*1024L) throw new java.io.IOException("Resource too large");
                    stream.write(buffer,0,count);
                }
            }
            if(!temp.renameTo(output)) {temp.delete();return null;}
            Log.i(TAG,"Guest resource staged "+virtualPath+
                    (entry==null?" from APK assets":" from OBB"));
            return output.getAbsolutePath();
        } catch(java.util.zip.ZipException invalid) {
            GuestStorageDiagnostics.report(expansionPath,false,"corrupt",android.os.SystemClock.elapsedRealtime());
            throw invalid;
        } catch(java.io.IOException failure) {
            if(!expansion.canRead()) GuestStorageDiagnostics.report(expansionPath,false,
                "unreadable",android.os.SystemClock.elapsedRealtime());
            throw failure;
        }
    }
    public static void recordGuestAchievement(String id) {
        Activity owner=active;
        if (owner==null || id==null) throw new IllegalStateException("Achievement unavailable");
        if (!owner.getSharedPreferences("PV7GuestAchievements", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(id,true).commit()) {
            throw new IllegalStateException("Unable to save achievement");
        }
    }

    private static volatile String expansionPath = "";

    /** Equivalent to the original game version-16 expansion lookup, with
     * the wrapper's own Android OBB directory substituted for guest storage. */
    public static String resolveExpansionPath() {
        File file = new File(expansionPath);
        boolean present = file.isFile() && file.canRead() && file.length()>0;
        GuestStorageDiagnostics.report(expansionPath,present,
            file.exists() ? "unreadable" : "missing",android.os.SystemClock.elapsedRealtime());
        return present ? expansionPath : "";
    }


}
