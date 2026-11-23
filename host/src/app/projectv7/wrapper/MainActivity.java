package app.projectv7.wrapper;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MainActivity extends Activity {
    public static native void nativeQueueText(String text,boolean closed,boolean canceled);
    static {
        System.loadLibrary("pv7host");
    }

    private static native void nativeConfigureCompat(String configText);
    private static native String nativeRunSelfTest(String originalLibMainPath,
                                                   String originalLibUnityPath,
                                                   String originalLibMonoPath,
                                                   String managedDirPath,
                                                   String packageCodePath,
                                                   Surface renderSurface,
                                                   int hostDisplayId);
    private static native String nativeRunUnityJniStage(String originalLibUnityPath,
                                                        int eventLimit);
    private static native void nativeSetGameLoopRunning(boolean running);
    private static native boolean nativeConsumeRestartRequest();
    private static native long nativeGuestFrameCount();
    private static native void nativeQueueTouch(int action,
                                                int metaState,
                                                int buttonState,
                                                int source,
                                                int deviceId,
                                                long downTimeNanos,
                                                long eventTimeNanos,
                                                int[] pointerIds,
                                                float[] xs,
                                                float[] ys,
                                                float[] pressures,
                                                float[] sizes);

    private static final String PREFS = "project-v7-wrapper";
    private static final String KEY_STAGE = "jni-stage-started";
    private static final String KEY_COMPLETED = "jni-stage-completed";
    private static final String KEY_LAST_SUCCESS = "jni-last-success";
    private static final String KEY_GUEST_ASSET_STAMP = "guest-assets-install-stamp-v1";

    // Keep the translated ARM32 renderer near the known-good pixel budget, but
    // always match the host display aspect ratio. A fixed 1016x577 buffer was
    // being stretched across wide phones, which made the JoyDo splash and IMGUI
    // mod controls look vertically squashed.
    private static final int GAME_BUFFER_LONG_EDGE = 1024;
    private int gameBufferWidth = 1016;
    private int gameBufferHeight = 577;
    private float touchLogicalWidth;
    private float touchLogicalHeight;
    private final Properties hostProperties = new Properties();

    private TextView output;
    private SurfaceView renderSurface;
    private final Object baselineLock = new Object();
    private volatile Thread baselineThread;
    private volatile boolean baselineStarted;
    private volatile boolean baselineRunning;
    private volatile boolean guestPreparationRunning;
    private volatile boolean restartWhenStopped;
    private volatile boolean activityResumed = true;
    private File originalLibMain;
    private File originalLibUnity;
    private File originalLibMono;
    private File managedDir;

    private static final long SPLASH_OPTIONS_MS = 3000L;
    private static final long STARTUP_TIMEOUT_MS = 90000L;
    private static final long GUEST_STALL_MS = 15000L;
    private static final String FLAG_PREFIX = "guest-launch-flag.";
    private static final String[] FLAG_KEYS = {
        "nativeClocks", "disableBlockLinking", "frameSleepDeadline"
    };
    private static final String[] FLAG_LABELS = {
        "Native POSIX clocks (experimental)",
        "Disable direct block linking (ARM64)",
        "Cooperative frame sleep deadlines (supported modules)"
    };
    private final Handler supervisor = new Handler(Looper.getMainLooper());
    private FrameLayout rootLayout;
    private LinearLayout guestOverlay;
    private ImageView splashLogo;
    private TextView guestStatus;
    private ProgressBar guestSpinner;
    private Button guestAction;
    private boolean launchReady;
    private boolean flagsOpen;
    private boolean compatConfigured;
    private boolean restartingGuest;
    private boolean heartbeatAvailable = true;
    private boolean restartRequestAvailable = true;
    private volatile boolean guestPreparationFailed;
    private boolean sawGuestFrame;
    private boolean showingRecovery;
    private long lastGuestFrameCount;
    private long lastGuestProgress;
    private final Runnable releaseLaunch = () -> {
        if (flagsOpen || restartingGuest || isFinishing()) return;
        launchReady = true;
        if (guestAction != null) guestAction.setVisibility(View.GONE);
        if (guestStatus != null) { guestStatus.setText("Launching guest..."); guestStatus.setVisibility(View.VISIBLE); }
        if (guestSpinner != null) guestSpinner.setVisibility(View.VISIBLE);
        tryStartGuest();
    };
    private final Runnable guestWatchdog = new Runnable() {
        @Override public void run() {
            if (!activityResumed || restartingGuest || isFinishing()) return;
            pollGuest();
            if (!restartingGuest) supervisor.postDelayed(this, 500);
        }
    };

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildGuestOverlay() {
        rootLayout = new FrameLayout(this);
        rootLayout.addView(renderSurface, new FrameLayout.LayoutParams(-1, -1));
        guestOverlay = new LinearLayout(this);
        guestOverlay.setOrientation(LinearLayout.VERTICAL);
        guestOverlay.setGravity(Gravity.CENTER);
        guestOverlay.setBackgroundColor(0xff000000);
        guestOverlay.setClickable(true);
        guestOverlay.setPadding(dp(24), dp(16), dp(24), dp(16));
        splashLogo = new ImageView(this);
        splashLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try (InputStream input = getAssets().open("pv7/splash-logo.png")) {
            splashLogo.setImageBitmap(BitmapFactory.decodeStream(input));
        } catch (Exception missingLogo) {
            Log.w("PV7HOST", "PV7 splash logo unavailable", missingLogo);
        }
        guestOverlay.addView(splashLogo, new LinearLayout.LayoutParams(dp(220), 0, 1f));
        guestStatus = new TextView(this);
        guestStatus.setTextColor(0xffeeeeee);
        guestStatus.setTextSize(16);
        guestStatus.setGravity(Gravity.CENTER);
        guestStatus.setText("");
        guestStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(4);
        guestOverlay.addView(guestStatus, statusParams);
        guestSpinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        spinnerParams.topMargin = dp(12);
        guestOverlay.addView(guestSpinner, spinnerParams);
        guestSpinner.setVisibility(View.GONE);
        guestAction = new Button(this);
        guestAction.setText("Pause & change flags");
        guestAction.setOnClickListener(v -> showLaunchFlags());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = dp(12);
        guestOverlay.addView(guestAction, actionParams);
        rootLayout.addView(guestOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootLayout);
    }

    private void tryStartGuest() {
        if (!activityResumed || !launchReady || restartingGuest || renderSurface == null) return;
        Surface surface = renderSurface.getHolder().getSurface();
        if (surface != null && surface.isValid() && configureGameBufferForView()) startBaselineOnce();
    }

    private void showLaunchFlags() {
        if (launchReady || flagsOpen || restartingGuest) return;
        flagsOpen = true;
        supervisor.removeCallbacks(releaseLaunch);
        guestStatus.setText("Guest launch paused");
        guestStatus.setVisibility(View.VISIBLE);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(12), dp(24), dp(8));
        TextView help = new TextView(this);
        help.setText("Default preserves the selected module's behavior. Overrides are saved for this game and applied before the guest starts.");
        form.addView(help);
        Spinner[] controls = new Spinner[FLAG_KEYS.length];
        SharedPreferences prefs = getPreferencesStore();
        for (int i = 0; i < FLAG_KEYS.length; i++) {
            TextView label = new TextView(this);
            label.setText(FLAG_LABELS[i]);
            label.setPadding(0, dp(14), 0, 0);
            form.addView(label);
            Spinner control = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"Module default", "On", "Off"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            control.setAdapter(adapter);
            String saved = prefs.getString(FLAG_PREFIX + FLAG_KEYS[i], "");
            control.setSelection("true".equals(saved) ? 1 : "false".equals(saved) ? 2 : 0);
            controls[i] = control;
            form.addView(control, fullWidthButtonParams());
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Guest launch flags")
            .setView(scroll)
            .setPositiveButton("Save & launch", (d, which) -> {
                SharedPreferences.Editor editor = prefs.edit();
                for (int i = 0; i < FLAG_KEYS.length; i++) {
                    int choice = controls[i].getSelectedItemPosition();
                    String key = FLAG_PREFIX + FLAG_KEYS[i];
                    if (choice == 0) editor.remove(key);
                    else editor.putString(key, choice == 1 ? "true" : "false");
                }
                editor.commit();
            })
            .setNeutralButton("Reset defaults", (d, which) -> {
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : FLAG_KEYS) editor.remove(FLAG_PREFIX + key);
                editor.commit();
            })
            .setNegativeButton("Launch unchanged", null)
            .create();
        dialog.setOnDismissListener(d -> {
            flagsOpen = false;
            releaseLaunch.run();
        });
        dialog.show();
    }

    private void showLostGuest() {
        if (restartingGuest || showingRecovery) return;
        showingRecovery = true;
        guestOverlay.setVisibility(View.VISIBLE);
        guestStatus.setText("Lost connection to the guest");
        guestStatus.setVisibility(View.VISIBLE);
        guestSpinner.setVisibility(View.VISIBLE);
        guestAction.setText("Restart guest");
        guestAction.setVisibility(View.VISIBLE);
        guestAction.setOnClickListener(v -> restartGuestProcess("manual recovery"));
    }

    private void pollGuest() {
        if (restartRequestAvailable && launchReady) {
            try {
                if (nativeConsumeRestartRequest()) {
                    restartGuestProcess("guest restart request");
                    return;
                }
            } catch (UnsatisfiedLinkError unavailable) { restartRequestAvailable = false; }
        }
        if (!launchReady || !compatConfigured) return;
        long now = SystemClock.elapsedRealtime();
        if (heartbeatAvailable && baselineRunning) {
            try {
                long count = nativeGuestFrameCount();
                if (count != lastGuestFrameCount || (!sawGuestFrame && count > 0)) {
                    lastGuestFrameCount = count;
                    lastGuestProgress = now;
                    sawGuestFrame = true;
                    showingRecovery = false;
                    guestOverlay.setVisibility(View.GONE);
                }
            } catch (UnsatisfiedLinkError unavailable) {
                heartbeatAvailable = false;
                guestOverlay.setVisibility(View.GONE);
                Log.w("PV7HOST", "Guest heartbeat unavailable in this native module");
            }
        }
        if (lastGuestProgress == 0L) lastGuestProgress = now;
        if (guestPreparationFailed || (!baselineRunning && !guestPreparationRunning && baselineStarted && !restartWhenStopped)) {
            showLostGuest();
        } else if (heartbeatAvailable && now - lastGuestProgress >=
                   (sawGuestFrame ? GUEST_STALL_MS : STARTUP_TIMEOUT_MS)) {
            showLostGuest();
        }
    }

    private void restartGuestProcess(String reason) {
        if (restartingGuest || isFinishing()) return;
        restartingGuest = true;
        supervisor.removeCallbacksAndMessages(null);
        guestOverlay.setVisibility(View.VISIBLE);
        guestStatus.setText("Restarting guest...");
        guestStatus.setVisibility(View.VISIBLE);
        guestSpinner.setVisibility(View.VISIBLE);
        guestAction.setVisibility(View.GONE);
        Log.i("PV7HOST", "PV7LIFE restart requested: " + reason);
        try {
            Intent intent = new Intent(this, GuestRestartActivity.class);
            intent.putExtra("pv7.guestPid", Process.myPid());
            if (Build.VERSION.SDK_INT >= 26) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(getWindowManager().getDefaultDisplay().getDisplayId());
                startActivity(intent, options.toBundle());
            } else startActivity(intent);
        } catch (RuntimeException failure) {
            restartingGuest = false;
            showingRecovery = false;
            Log.e("PV7HOST", "Could not launch guest recovery activity", failure);
            showLostGuest();
            if (activityResumed) supervisor.postDelayed(guestWatchdog, 500);
        }
    }

    private boolean configureGameBufferForView() {
        int viewWidth = renderSurface != null ? renderSurface.getWidth() : 0;
        int viewHeight = renderSurface != null ? renderSurface.getHeight() : 0;
        if (viewWidth <= 0 || viewHeight <= 0) {
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            viewWidth = Math.max(1, metrics.widthPixels);
            viewHeight = Math.max(1, metrics.heightPixels);
        }

        if (viewWidth >= viewHeight) {
            gameBufferWidth = GAME_BUFFER_LONG_EDGE;
            gameBufferHeight = Math.max(1, Math.round((float) GAME_BUFFER_LONG_EDGE * viewHeight / viewWidth));
        } else {
            gameBufferHeight = GAME_BUFFER_LONG_EDGE;
            gameBufferWidth = Math.max(1, Math.round((float) GAME_BUFFER_LONG_EDGE * viewWidth / viewHeight));
        }

        Rect frame = renderSurface.getHolder().getSurfaceFrame();
        if (frame.width() != gameBufferWidth || frame.height() != gameBufferHeight) {
            renderSurface.getHolder().setFixedSize(gameBufferWidth, gameBufferHeight);
            Log.i("PV7HOST", "PV7SURFACE reconfigure view=" + viewWidth + "x" + viewHeight
                    + " buffer=" + gameBufferWidth + "x" + gameBufferHeight);
            return false;
        }
        Log.i("PV7HOST", "PV7SURFACE ready view=" + viewWidth + "x" + viewHeight
                + " buffer=" + gameBufferWidth + "x" + gameBufferHeight);
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HostKeyboard.attach(this);
        configureHostOptions();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File guestSharedDir = new File(getFilesDir(), "GuestSharedStorage");
        if (!guestSharedDir.exists() && !guestSharedDir.mkdirs()) {
            Log.w("PV7HOST", "Could not create guest shared-storage sandbox: " + guestSharedDir);
        }

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // Keep diagnostics available to logcat without exposing the old PoC UI.
        output = new TextView(this);

        renderSurface = new SurfaceView(this);
        configureGameBufferForView();
        renderSurface.setClickable(true);
        renderSurface.setFocusable(true);
        renderSurface.setFocusableInTouchMode(true);
        renderSurface.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                final int pointerCount = Math.min(event.getPointerCount(), 16);
                Log.i("PV7HOST", "PV7JAVA touch action=" + event.getActionMasked()
                        + " x=" + event.getX() + " y=" + event.getY()
                        + " view=" + renderSurface.getWidth() + "x" + renderSurface.getHeight());
                if ((!sawGuestFrame && heartbeatAvailable) || restartingGuest || showingRecovery || pointerCount <= 0) return true;
                int[] ids = new int[pointerCount];
                float[] xs = new float[pointerCount];
                float[] ys = new float[pointerCount];
                float[] pressures = new float[pointerCount];
                float[] sizes = new float[pointerCount];
                Rect surfaceFrame = renderSurface.getHolder().getSurfaceFrame();
                float touchScaleX = renderSurface.getWidth() > 0 && surfaceFrame.width() > 0
                        ? (float) surfaceFrame.width() / (float) renderSurface.getWidth() : 1.0f;
                float touchScaleY = renderSurface.getHeight() > 0 && surfaceFrame.height() > 0
                        ? (float) surfaceFrame.height() / (float) renderSurface.getHeight() : 1.0f;
                for (int i = 0; i < pointerCount; ++i) {
                    ids[i] = event.getPointerId(i);                    float logicalWidth = touchLogicalWidth > 0.0f ? touchLogicalWidth : gameBufferWidth;
                    float logicalHeight = touchLogicalHeight > 0.0f ? touchLogicalHeight : gameBufferHeight;
                    xs[i] = event.getX(i) * touchScaleX * logicalWidth / gameBufferWidth;
                    ys[i] = event.getY(i) * touchScaleY * logicalHeight / gameBufferHeight;
                    pressures[i] = event.getPressure(i);
                    sizes[i] = event.getSize(i);
                }
                nativeQueueTouch(event.getAction(),
                                 event.getMetaState(),
                                 event.getButtonState(),
                                 event.getSource(),
                                 event.getDeviceId(),
                                 event.getDownTime() * 1000000L,
                                 event.getEventTime() * 1000000L,
                                 ids, xs, ys, pressures, sizes);
                return true;
            }
        });
        renderSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i("PV7HOST", "PV7LIFE surfaceCreated running=" + baselineRunning + " resumed=" + activityResumed);
                baselineStarted = false;
                if (baselineRunning) restartWhenStopped = true;
                // Wait for surfaceChanged so the fixed buffer can be matched to
                // the actual laid-out SurfaceView aspect ratio before Unity starts.
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Rect frame = holder.getSurfaceFrame();
                Log.i("PV7HOST", "PV7LIFE surfaceChanged view=" + renderSurface.getWidth() + "x" + renderSurface.getHeight()
                        + " buffer=" + frame.width() + "x" + frame.height());
                if (!configureGameBufferForView()) return;
                if (activityResumed && !baselineRunning) {
                    baselineStarted = false;
                    startBaselineOnce();
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i("PV7HOST", "PV7LIFE surfaceDestroyed running=" + baselineRunning + " resumed=" + activityResumed);
                restartWhenStopped = true;
                baselineStarted = false;
                nativeSetGameLoopRunning(false);
            }
        });

        buildGuestOverlay();
        renderSurface.requestFocus();
        supervisor.postDelayed(releaseLaunch, SPLASH_OPTIONS_MS);


    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i("PV7HOST", "PV7LIFE onResume running=" + baselineRunning);
        activityResumed = true;
        lastGuestProgress = SystemClock.elapsedRealtime();
        supervisor.removeCallbacks(guestWatchdog);
        supervisor.post(guestWatchdog);
        if (!baselineRunning && renderSurface != null) {
            Surface surface = renderSurface.getHolder().getSurface();
            if (surface != null && surface.isValid() && configureGameBufferForView()) {
                baselineStarted = false;
                startBaselineOnce();
            }
        }
    }

    @Override
    protected void onPause() {
        Log.i("PV7HOST", "PV7LIFE onPause running=" + baselineRunning);
        activityResumed = false;
        supervisor.removeCallbacks(guestWatchdog);
        // Do not tear the translated Unity runtime down merely because another
        // Activity covered us. If Android actually destroys the Surface,
        // surfaceDestroyed() performs the stop/restart handoff.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        supervisor.removeCallbacksAndMessages(null);
        restartWhenStopped = false;
        nativeSetGameLoopRunning(false);
        super.onDestroy();
    }

    private LinearLayout.LayoutParams fullWidthButtonParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private SharedPreferences getPreferencesStore() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void configureCompatibility() {
        String text = "";
        try (InputStream input = getAssets().open("pv7/compat.properties");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            text = output.toString("UTF-8");
        } catch (Throwable ignored) {
            // No compatibility payload is a valid generic wrapper.
        }
        StringBuilder config = new StringBuilder(text).append('\n');
        for (String key : FLAG_KEYS) {
            String value = getPreferencesStore().getString(FLAG_PREFIX + key, "");
            if ("true".equals(value) || "false".equals(value))
                config.append("property.pv7.flag.").append(key).append('=').append(value).append('\n');
        }
        nativeConfigureCompat(config.toString());
        Log.i("PV7HOST", "Compatibility payload bytes=" + text.length());
    }

    private void configureHostOptions() {
        hostProperties.clear();
        try (InputStream input = getAssets().open("pv7/host.properties")) {
            hostProperties.load(input);
            touchLogicalWidth = parsePositiveFloat(hostProperties.getProperty("touch.logicalWidth"));
            touchLogicalHeight = parsePositiveFloat(hostProperties.getProperty("touch.logicalHeight"));
        } catch (Throwable ignored) {
            touchLogicalWidth = 0.0f;
            touchLogicalHeight = 0.0f;
        }
        Log.i("PV7HOST", "Host options touch=" + touchLogicalWidth + "x" + touchLogicalHeight);
    }

    private static float parsePositiveFloat(String value) {
        if (value == null) return 0.0f;
        try {
            float parsed = Float.parseFloat(value.trim());
            return parsed > 0.0f ? parsed : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private String readAssetText(String assetName) throws Exception {
        try (InputStream input = getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toString("US-ASCII");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        final char[] hex = "0123456789ABCDEF".toCharArray();
        byte[] bytes = digest.digest();
        char[] text = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            int value = bytes[i] & 0xff;
            text[i * 2] = hex[value >>> 4];
            text[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(text);
    }

    private int migrateLegacyCnrModsTree(File source, File destination) throws Exception {
        if (!source.exists()) return 0;

        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IllegalStateException("Could not create migration directory " + destination);
            }
            if (!destination.isDirectory()) return 0;
            int migrated = 0;
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    migrated += migrateLegacyCnrModsTree(child, new File(destination, child.getName()));
                }
            }
            return migrated;
        }

        // Never overwrite the private root. It may contain a newer bootstrap file,
        // self-updated config, or files the user has already edited through SAF.
        if (destination.exists()) return 0;
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create migration parent " + parent);
        }

        File staged = new File(parent, destination.getName() + ".pv7-migrate");
        if (staged.exists() && !staged.delete()) {
            throw new IllegalStateException("Could not clear staged migration file " + staged);
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(staged, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        }
        if (!staged.renameTo(destination)) {
            staged.delete();
            throw new IllegalStateException("Could not migrate " + source + " to " + destination);
        }
        destination.setLastModified(source.lastModified());
        return 1;
    }

    private void migrateLegacyCnrModsIfAvailable() {
        File legacyRoot = new File(Environment.getExternalStorageDirectory(), "CNRMods");
        File privateRoot = new File(getFilesDir(), "CNRMods");
        try {
            if (!legacyRoot.isDirectory()) return;
            if (!legacyRoot.canRead()) {
                Log.w("PV7HOST", "Legacy CNRMods exists but is not readable: " + legacyRoot);
                return;
            }
            if (legacyRoot.getCanonicalFile().equals(privateRoot.getCanonicalFile())) return;
            int migrated = migrateLegacyCnrModsTree(legacyRoot, privateRoot);
            if (migrated > 0) {
                Log.i("PV7HOST", "Migrated " + migrated + " legacy CNRMods files from " + legacyRoot);
                CNRModsDocumentsProvider.notifyRootChanged(this);
            }
        } catch (Throwable t) {
            // Migration is compatibility-only. Never block the wrapped app if Android denies
            // the old shared-storage path or a legacy file cannot be copied.
            Log.w("PV7HOST", "Legacy CNRMods migration skipped", t);
        }
    }

    private File safeFilesChild(String relativePath) throws Exception {
        File root = getFilesDir().getCanonicalFile();
        File target = new File(root, relativePath).getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath)
                && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IllegalArgumentException("Bootstrap path escapes files directory: " + relativePath);
        }
        return target;
    }

    private void seedBootstrapFiles() throws Exception {
        SharedPreferences prefs = getPreferencesStore();
        for (int i = 0; i < 64; ++i) {
            String prefix = "bootstrap." + i + ".";
            String assetName = hostProperties.getProperty(prefix + "asset");
            String targetRelative = hostProperties.getProperty(prefix + "toFilesRelative");
            if ((assetName == null || assetName.trim().isEmpty())
                    && (targetRelative == null || targetRelative.trim().isEmpty())) {
                continue;
            }
            if (assetName == null || assetName.trim().isEmpty()
                    || targetRelative == null || targetRelative.trim().isEmpty()) {
                throw new IllegalStateException("Incomplete bootstrap entry " + i);
            }
            assetName = assetName.trim();
            targetRelative = targetRelative.trim();

            File target = safeFilesChild(targetRelative);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Could not create bootstrap directory " + parent);
            }

            String hashAsset = hostProperties.getProperty(prefix + "sha256Asset");
            String expectedHash = null;
            if (hashAsset != null && !hashAsset.trim().isEmpty()) {
                expectedHash = readAssetText(hashAsset.trim()).trim().toUpperCase();
                if (!expectedHash.matches("[0-9A-F]{64}")) {
                    throw new IllegalStateException("Invalid SHA-256 marker for bootstrap entry " + i);
                }
            }

            boolean preserveCustom = Boolean.parseBoolean(
                    hostProperties.getProperty(prefix + "preserveCustom", "true"));
            String currentHash = target.isFile() ? sha256(target) : "";
            String prefKey = "bootstrap-seed-sha256-" + i;
            String previousSeedHash = prefs.getString(prefKey, "").trim().toUpperCase();

            boolean alreadyCurrent = expectedHash != null && expectedHash.equals(currentHash);
            boolean mayReplace = !target.isFile()
                    || !preserveCustom
                    || (expectedHash != null && currentHash.equals(previousSeedHash));

            if (target.isFile() && !alreadyCurrent && !mayReplace) {
                Log.i("PV7HOST", "Preserving custom bootstrap file " + targetRelative
                        + " sha256=" + currentHash);
                continue;
            }

            if (!alreadyCurrent) {
                File staged = new File(target.getPath() + ".new");
                if (staged.exists() && !staged.delete()) {
                    throw new IllegalStateException("Could not clear staged bootstrap file " + staged);
                }
                try (InputStream input = getAssets().open(assetName);
                     FileOutputStream output = new FileOutputStream(staged, false)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) output.write(buffer, 0, read);
                    }
                    output.flush();
                    output.getFD().sync();
                }

                String stagedHash = sha256(staged);
                if (expectedHash != null && !expectedHash.equals(stagedHash)) {
                    staged.delete();
                    throw new IllegalStateException(
                            "Bootstrap hash mismatch for " + targetRelative + ": " + stagedHash);
                }
                if (target.exists() && !target.delete()) {
                    staged.delete();
                    throw new IllegalStateException("Could not replace bootstrap file " + target);
                }
                if (!staged.renameTo(target)) {
                    staged.delete();
                    throw new IllegalStateException("Could not install bootstrap file " + target);
                }
                currentHash = stagedHash;
                Log.i("PV7HOST", "Seeded bootstrap " + targetRelative + " sha256=" + currentHash);
            }

            prefs.edit().putString(prefKey,
                    expectedHash != null ? expectedHash : currentHash).commit();
        }
    }

    private void startBaselineOnce() {
        synchronized (baselineLock) {
            if (!launchReady || restartingGuest || baselineStarted || guestPreparationRunning || baselineRunning || !activityResumed
                    || (baselineThread != null && baselineThread.isAlive())) return;
            Surface surface = renderSurface.getHolder().getSurface();
            if (surface == null || !surface.isValid()) return;
            baselineStarted = true;
            guestPreparationRunning = true;
            guestPreparationFailed = false;
            restartWhenStopped = false;
            if (!compatConfigured) {
                configureCompatibility();
                compatConfigured = true;
            }
            lastGuestProgress = SystemClock.elapsedRealtime();
            lastGuestFrameCount = 0;
            sawGuestFrame = false;
            showingRecovery = false;
        }
        // The old code executed ~200 MiB of guest APK extraction, bootstrap
        // hashing and legacy-mod migration synchronously on the UI thread.
        // This blocked Android input/redraw and delayed Unity/mod startup.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    prepareGuestsAndRunSafeBaseline();
                } finally {
                    boolean restart;
                    synchronized (baselineLock) {
                        guestPreparationRunning = false;
                        restart = !baselineRunning && restartWhenStopped && activityResumed;
                        if (!baselineRunning) baselineStarted = false;
                    }
                    // If the Surface changed while extraction ran, restart once
                    // the replacement becomes valid. Do not retry failed guest
                    // preparation in a hot loop.
                    if (restart) runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing() && renderSurface != null
                                    && renderSurface.getHolder().getSurface().isValid()) {
                                startBaselineOnce();
                            }
                        }
                    });
                }
            }
        }, "PV7-GuestPrepare").start();
    }

    private File prepareGuestAsset(String assetName, String outputName) throws Exception {
        File out = new File(getFilesDir(), outputName);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }
        try (InputStream input = getAssets().open(assetName);
             FileOutputStream stream = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) stream.write(buffer, 0, read);
            }
            stream.flush();
        }
        return out;
    }

    private void prepareGuestAssetTree(String assetPath, String outputPath) throws Exception {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            prepareGuestAsset(assetPath, outputPath);
            return;
        }

        File dir = new File(getFilesDir(), outputPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create " + dir);
        }
        for (String child : children) {
            prepareGuestAssetTree(assetPath + "/" + child, outputPath + "/" + child);
        }
    }

    private File prepareGuestDataDirectory() throws Exception {
        prepareGuestAssetTree("bin/Data", "Data");
        File managed = new File(getFilesDir(), "Data/Managed");
        if (!new File(managed, "mscorlib.dll").isFile()) {
            throw new IllegalStateException("Packaged Unity Data/Managed directory is incomplete");
        }
        return managed;
    }

    private String guestAssetInstallStamp() throws Exception {
        // Compare the ZIP's original guest payload, not wrapper install time.
        // Rebuilding PV7 Java/native code must not unpack 200 MiB again.
        ArrayList<String> entries = new ArrayList<String>();
        try (ZipFile apk = new ZipFile(getPackageCodePath())) {
            Enumeration<? extends ZipEntry> all = apk.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (!name.startsWith("assets/bin/Data/")
                        && !name.startsWith("assets/guest/")) continue;
                entries.add(name + ":" + entry.getSize() + ":" + entry.getCrc());
            }
        }
        if (entries.size() < 4) {
            throw new IllegalStateException("Guest asset inventory is incomplete");
        }
        Collections.sort(entries);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String item : entries) {
            digest.update(item.getBytes("UTF-8"));
            digest.update((byte) 10);
        }
        final char[] hex = "0123456789abcdef".toCharArray();
        byte[] bytes = digest.digest();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            result[i * 2] = hex[(bytes[i] & 0xff) >>> 4];
            result[i * 2 + 1] = hex[bytes[i] & 0x0f];
        }
        return new String(result);
    }

    private void prepareGuestFilesCached() throws Exception {
        final long began = android.os.SystemClock.elapsedRealtime();
        SharedPreferences prefs = getPreferencesStore();
        String stamp = guestAssetInstallStamp();
        File files = getFilesDir();
        File main = new File(files, "pv7-guest-libmain.so");
        File unity = new File(files, "pv7-guest-libunity.so");
        File mono = new File(files, "pv7-guest-libmono.so");
        File managed = new File(files, "Data/Managed");
        boolean ready = stamp.equals(prefs.getString(KEY_GUEST_ASSET_STAMP, ""))
                && main.isFile() && main.length() > 0
                && unity.isFile() && unity.length() > 0
                && mono.isFile() && mono.length() > 0
                && new File(files, "Data/mainData").isFile()
                && new File(managed, "mscorlib.dll").isFile();
        if (!ready) {
            // Never accept a partially copied asset tree as complete.
            prefs.edit().remove(KEY_GUEST_ASSET_STAMP).commit();
            main = prepareGuestAsset("guest/libmain.so", "pv7-guest-libmain.so");
            unity = prepareGuestAsset("guest/libunity.so", "pv7-guest-libunity.so");
            mono = prepareGuestAsset("guest/libmono.so", "pv7-guest-libmono.so");
            managed = prepareGuestDataDirectory();
            if (!prefs.edit().putString(KEY_GUEST_ASSET_STAMP, stamp).commit()) {
                Log.w("PV7HOST", "Guest asset cache stamp could not be persisted");
            }
        }
        originalLibMain = main;
        originalLibUnity = unity;
        originalLibMono = mono;
        managedDir = managed;
        Log.i("PV7HOST", "PV7STARTUP guest-assets " + (ready ? "cache-hit" : "extracted")
                + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - began));
    }

    private void setOutputStatus(final String message) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            output.setText(message);
        } else {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) output.setText(message);
                }
            });
        }
    }

    private String environmentHeader() {
        StringBuilder text = new StringBuilder();
        text.append("Android ABI(s): ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        text.append("Process.is64Bit(): ").append(Process.is64Bit()).append('\n');
        text.append("Host wrapper ABI: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown").append('\n');
        text.append("ARM32 libmain/libunity/libmono are guest assets only.\n\n");
        return text.toString();
    }

    private void prepareGuestsAndRunSafeBaseline() {
        StringBuilder text = new StringBuilder(environmentHeader());
        SharedPreferences prefs = getPreferencesStore();
        int priorStage = prefs.getInt(KEY_STAGE, -1);
        boolean priorCompleted = prefs.getBoolean(KEY_COMPLETED, true);
        if (priorStage >= 0 && !priorCompleted) {
            text.append("IMPORTANT: previous process died/interrupted during Unity JNI ")
                .append(stageLabel(priorStage)).append(".\n")
                .append("That is the current crash boundary.\n\n");
        }
        setOutputStatus(text.toString() + "Preparing original guest binaries...\n");

        try {
            migrateLegacyCnrModsIfAvailable();
            seedBootstrapFiles();
            CNRModsDocumentsProvider.notifyRootChanged(this);
            prepareGuestFilesCached();
            if (activityResumed && !restartWhenStopped) runSafeBaseline();
        } catch (Throwable t) {
            guestPreparationFailed = true;
            Log.e("PV7HOST", "Guest preparation failed", t);
            setOutputStatus(text.toString() + "GUEST PREPARATION ERROR\n\n" + t);
        }
    }

    private void runSafeBaseline() {
        if (originalLibMain == null || originalLibUnity == null || originalLibMono == null || managedDir == null) return;
        String prefix = environmentHeader();
        SharedPreferences prefs = getPreferencesStore();
        int priorStage = prefs.getInt(KEY_STAGE, -1);
        boolean priorCompleted = prefs.getBoolean(KEY_COMPLETED, true);
        if (priorStage >= 0 && !priorCompleted) {
            prefix += "Previous process died/interrupted during " + stageLabel(priorStage) + ".\n\n";
        }
        final String baselinePrefix = prefix;
        final Surface surface = renderSurface.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            setOutputStatus(baselinePrefix + "Waiting for a valid render Surface...\n");
            return;
        }

        final File hotpatchDir = new File(getFilesDir(), "cnr64-hotpatch");
        if (!hotpatchDir.exists()) hotpatchDir.mkdirs();
        setOutputStatus(baselinePrefix + "Running SAFE baseline on worker thread...\n"
                + "Live compatibility extensions: " + hotpatchDir.getAbsolutePath() + "\n");
        final Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String result = nativeRunSelfTest(originalLibMain.getAbsolutePath(),
                                                            originalLibUnity.getAbsolutePath(),
                                                            originalLibMono.getAbsolutePath(),
                                                            managedDir.getAbsolutePath(),
                                                            MainActivity.this.getPackageCodePath(),
                                                            surface,
                                                            renderSurface.getDisplay() != null
                                                                    ? renderSurface.getDisplay().getDisplayId() : 0);
                    for (int offset = 0; offset < result.length(); offset += 3000) {
                        Log.i("PV7HOST", "SAFE_BASELINE_CHUNK " + offset + "\n"
                                + result.substring(offset, Math.min(offset + 3000, result.length())));
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            output.setText(baselinePrefix + result + "\n\nUnity JNI_OnLoad is intentionally NOT auto-run.\n"
                                    + "Use the checkpoint button below.");
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            output.setText(baselinePrefix + "SAFE NATIVE TEST ERROR\n\n" + t);
                        }
                    });
                } finally {
                    final boolean ownsActiveSlot;
                    synchronized (baselineLock) {
                        ownsActiveSlot = baselineThread == Thread.currentThread();
                        if (ownsActiveSlot) {
                            nativeSetGameLoopRunning(false);
                            baselineThread = null;
                            baselineRunning = false;
                        }
                    }
                    Log.i("PV7HOST", "PV7LIFE baseline worker finally active=" + ownsActiveSlot
                            + " restart=" + restartWhenStopped + " resumed=" + activityResumed);
                    if (ownsActiveSlot) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (restartWhenStopped && activityResumed && !isFinishing()) {
                                    Surface nextSurface = renderSurface.getHolder().getSurface();
                                    if (nextSurface != null && nextSurface.isValid()) {
                                        baselineStarted = false;
                                        startBaselineOnce();
                                    }
                                }
                            }
                        });
                    }
                }
            }
        }, "CNR64-Harness");

        synchronized (baselineLock) {
            if (baselineThread != null && baselineThread.isAlive()) {
                Log.w("PV7HOST", "PV7LIFE refusing duplicate CNR64-Harness launch");
                return;
            }
            baselineThread = worker;
            nativeSetGameLoopRunning(true);
            baselineRunning = true;
        }
        worker.start();
    }

    private void runUnityJniStage(int eventLimit) {
        if (originalLibUnity == null) return;

        SharedPreferences prefs = getPreferencesStore();
        prefs.edit()
             .putInt(KEY_STAGE, eventLimit)
             .putBoolean(KEY_COMPLETED, false)
             .commit();

        output.setText(environmentHeader()
                + "Running " + stageLabel(eventLimit) + "...\n\n"
                + "If the process dies, reopen the app; this stage will be remembered.\n");
        Log.i("PV7HOST", "JNI_STAGE_BEGIN=" + eventLimit + " " + stageLabel(eventLimit));

        try {
            String result = nativeRunUnityJniStage(originalLibUnity.getAbsolutePath(), eventLimit);
            boolean passed = result.contains("PASS");
            SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_COMPLETED, true);
            if (eventLimit > 0 && passed) {
                editor.putInt(KEY_LAST_SUCCESS, eventLimit);
            }
            editor.commit();
            output.setText(environmentHeader() + result + "\n\n"
                    + (passed ? "Checkpoint returned to Java successfully." : "Checkpoint returned with a reported failure."));
            Log.i("PV7HOST", "JNI_STAGE=" + eventLimit + "\n" + result);
        } catch (Throwable t) {
            prefs.edit().putBoolean(KEY_COMPLETED, true).commit();
            output.setText(environmentHeader() + "JNI STAGE JAVA/JNI ERROR\n\n" + t);
        }
    }

    private void copyAllResults() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("CNR64 results", output.getText()));
    }

    private String stageLabel(int eventLimit) {
        switch (eventLimit) {
            case 0: return "FULL JNI_OnLoad";
            case 1: return "checkpoint 1: AttachCurrentThread";
            case 2: return "checkpoint 2: first FindClass";
            case 3: return "checkpoint 3: first RegisterNatives";
            case 4: return "checkpoint 4: second FindClass";
            case 5: return "checkpoint 5: second RegisterNatives";
            case 6: return "checkpoint 6: third FindClass";
            case 7: return "checkpoint 7: third RegisterNatives";
            default: return "checkpoint " + eventLimit;
        }
    }
}
