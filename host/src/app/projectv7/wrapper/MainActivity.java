package app.projectv7.wrapper;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Dialog;
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
import android.widget.Switch;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.AdapterView;
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
    private static native long nativeGuestPresentCount();
    private static native String nativeGuestStartupStage();
    private static native void nativeSetGuestBackgrounded(boolean backgrounded);
    private static native void nativeSetGuestHostSurface(Surface surface);
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
    private boolean cocosBackend;
    private volatile String cocosStage = "Preparing Cocos guest";
    private void setEngineRunning(boolean running) {
        if (cocosBackend) { if (!running) CocosGuestRuntime.stop(); }
        else nativeSetGameLoopRunning(running);
    }

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

    private static final long SPLASH_OPTIONS_MS = 1500L;
    private static final long STARTUP_TIMEOUT_MS = 90000L;
    private static final long GUEST_STALL_MS = 30000L;
    private static final String FLAG_PREFIX = "guest-launch-flag.";
    private static final String CACHE_PREFIX = "guest-launch-cache.";
    private static final String[] CACHE_KEYS = {"codeMiB", "dispatchEntries", "victimEntries"};
    private static final String[] CACHE_LABELS = {
        "JIT code cache (MiB; larger uses more memory)",
        "Primary dispatch cache (ARM64 entries)",
        "Secondary victim cache (ARM64 entries)"
    };
    private static final String[][] CACHE_VALUES = {
        {"", "16", "32", "64", "96", "128"},
        {"", "4096", "8192", "16384", "32768", "65536", "131072", "262144"},
        {"", "0", "4096", "8192", "16384", "32768", "65536", "131072", "262144"}
    };
    private static final String[] FLAG_KEYS = {
        "nativeClocks", "disableBlockLinking", "frameSleepDeadline", "targetedDispatchInvalidation"
    };
    private static final String[] FLAG_LABELS = {
        "Native POSIX clocks (experimental)",
        "Disable direct block linking (ARM64)",
        "Cooperative frame sleep deadlines (supported modules)",
        "Targeted dispatch-cache invalidation (default on)"
    };
    private final Handler supervisor = new Handler(Looper.getMainLooper());
    private FrameLayout rootLayout;
    private PerformanceOverlay performanceOverlay;
    private LinearLayout guestOverlay;
    private LinearLayout recoveryBanner;
    private TextView startupStageCaption;
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
    private final GuestBlackFrameProbe blackFrameProbe=new GuestBlackFrameProbe();
    private boolean storageBlackWarning;
    private boolean blackSamplePending;
    private long nextBlackSample;
    private int storageProbeGeneration;
    private long lastGuestFrameCount;
    private long lastGuestPresentCount;
    private boolean extendedHeartbeat = true;
    private boolean nativeStagesAvailable = true;
    private boolean backgroundControlAvailable = true;
    private boolean surfaceRebindAvailable = true;
    private boolean surfaceNeedsRebind;
    private boolean setGuestHostSurface(Surface surface) {
        if (cocosBackend || !surfaceRebindAvailable) return false;
        try {
            nativeSetGuestHostSurface(surface);
            return true;
        } catch (UnsatisfiedLinkError unavailable) {
            surfaceRebindAvailable = false;
            return false;
        }
    }
    private volatile String javaStartupStage = "Preparing guest";
    private boolean guestLoopReached;
    private void setGuestBackgrounded(boolean value) {
        if (cocosBackend) { CocosGuestRuntime.background(value); return; }
        if (!backgroundControlAvailable) return;
        try { nativeSetGuestBackgrounded(value); }
        catch (UnsatisfiedLinkError unavailable) { backgroundControlAvailable = false; }
    }
    private void setJavaStartupStage(String stage) {
        javaStartupStage = stage;
        Log.i("PV7HOST","PV7PHASE "+stage);
    }
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
        if (cocosBackend) {
            FrameLayout canvas = new FrameLayout(this) {
                @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight) {
                    super.onSizeChanged(width,height,oldWidth,oldHeight);
                    if(width>0 && height>0) {
                        int w=Math.min(width,Math.round(height*1.5f)), h=Math.min(height,Math.round(width/1.5f));
                        renderSurface.setLayoutParams(new FrameLayout.LayoutParams(w,h,android.view.Gravity.CENTER));
                    }
                }
            };
            canvas.setBackgroundColor(android.graphics.Color.BLACK);
            canvas.addView(renderSurface,new FrameLayout.LayoutParams(-1,-1));
            rootLayout.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        } else rootLayout.addView(renderSurface, new FrameLayout.LayoutParams(-1, -1));
        performanceOverlay = new PerformanceOverlay(this,rootLayout);
        performanceOverlay.configure(PerformanceOverlay.Settings.read(getPreferencesStore()));
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
        guestAction.setText("Pause & configure");
        guestAction.setOnClickListener(v -> showLaunchFlags());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = dp(12);
        guestOverlay.addView(guestAction, actionParams);
        rootLayout.addView(guestOverlay, new FrameLayout.LayoutParams(-1, -1));
        startupStageCaption = new TextView(this);
        startupStageCaption.setTextColor(0xffeeeeee);
        startupStageCaption.setTextSize(12);
        startupStageCaption.setPadding(dp(10),dp(6),dp(10),dp(6));
        startupStageCaption.setBackgroundColor(0x99000000);
        rootLayout.addView(startupStageCaption,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        startupStageCaption.setVisibility(View.GONE);
        recoveryBanner = new LinearLayout(this);
        recoveryBanner.setOrientation(LinearLayout.HORIZONTAL);
        recoveryBanner.setGravity(Gravity.CENTER_VERTICAL);
        recoveryBanner.setPadding(dp(8),dp(4),dp(8),dp(4));
        recoveryBanner.setBackgroundColor(0xe6222222);
        ProgressBar recoverySpinner = new ProgressBar(this);
        recoveryBanner.addView(recoverySpinner,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView warning = new TextView(this);
        warning.setText("Guest not responding");
        warning.setTextColor(0xffffffff);
        warning.setPadding(dp(8),0,dp(8),0);
        recoveryBanner.addView(warning);
        Button recover = new Button(this);
        recover.setText("Restart guest");
        recover.setOnClickListener(v -> restartGuestProcess("manual recovery"));
        recoveryBanner.addView(recover);
        rootLayout.addView(recoveryBanner,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
        recoveryBanner.setVisibility(View.GONE);
        setContentView(rootLayout);
    }

    private static final int SUPERVISOR_NOTIFICATION_ID = 0x7057;
    private static final int COMPATIBILITY_NOTIFICATION_ID = 0x7058;
    static synchronized void persistCompatibilityEvent(android.content.Context context,GuestCompatibilityEvents.Event e) {
        Log.w("PV7COMPAT",e.backend+" "+e.message());
        try {
            File file=new File(context.getFilesDir(),"pv7-compatibility-events.log");
            if(file.length()>64*1024L){
                File older=new File(context.getFilesDir(),"pv7-compatibility-events.previous.log");
                if(older.exists()&&!older.delete())return;
                if(!file.renameTo(older))return;
            }
            try(FileOutputStream out=new FileOutputStream(file,true)){
                out.write((System.currentTimeMillis()+" "+e.backend+" "+e.message()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch(java.io.IOException | RuntimeException unavailable) {
            Log.w("PV7COMPAT","Compatibility journal unavailable",unavailable);
        }
    }
    private void showCompatibilityNotice(long now) {
        GuestCompatibilityEvents.Event e=GuestCompatibilityEvents.poll(now);
        if(e==null)return;
        String message=e.message();
        // Toast is available even if the user disabled Android notifications.
        if(!e.critical)android.widget.Toast.makeText(this,"PV7: "+e.detail,android.widget.Toast.LENGTH_LONG).show();
        try {
            NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(manager==null || !manager.areNotificationsEnabled())return;
            String channel="pv7-compatibility";
            if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(
                channel,"PV7 compatibility warnings",NotificationManager.IMPORTANCE_DEFAULT));
            Intent open=new Intent(this,MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tap=PendingIntent.getActivity(this,COMPATIBILITY_NOTIFICATION_ID,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(e.critical?"PV7 compatibility error":"PV7 compatibility warning")
                .setContentText(e.detail).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true);
            manager.notify(COMPATIBILITY_NOTIFICATION_ID,builder.build());
        } catch(RuntimeException unavailable){Log.w("PV7COMPAT","Warning notification unavailable",unavailable);}
    }
    private static final String SUPERVISOR_CHANNEL = "pv7-supervisor";

    // An ongoing notification is a return-to-game shortcut, not a foreground service.
    // Android remains in control of process lifetime and notification permission.
    private void updateSupervisorNotification(boolean paused) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(new NotificationChannel(
                    SUPERVISOR_CHANNEL, "PV7 supervisor", NotificationManager.IMPORTANCE_LOW));
            }
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return;
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tap = PendingIntent.getActivity(this, SUPERVISOR_NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, SUPERVISOR_CHANNEL) : new Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getApplicationInfo().loadLabel(getPackageManager()))
                .setContentText(paused ? "PV7 guest paused · tap to resume" : "PV7 supervisor active")
                .setContentIntent(tap).setOngoing(true).setOnlyAlertOnce(true)
                .setShowWhen(false).setPriority(Notification.PRIORITY_LOW);
            manager.notify(SUPERVISOR_NOTIFICATION_ID, builder.build());
        } catch (RuntimeException unavailable) {
            Log.w("PV7HOST", "Supervisor notification unavailable", unavailable);
        }
    }

    private void clearSupervisorNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(SUPERVISOR_NOTIFICATION_ID);
    }

    private void tryStartGuest() {
        if (!activityResumed || !launchReady || restartingGuest || renderSurface == null) return;
        Surface surface = renderSurface.getHolder().getSurface();
        if (surface != null && surface.isValid() && configureGameBufferForView()) startBaselineOnce();
    }

    private void addOverlaySettings(LinearLayout form, FrameLayout previewPanel, TextView preview, PerformanceOverlay.Settings config) {
        TextView heading = new TextView(this);
        heading.setText("Performance overlay");
        heading.setTextSize(20);
        heading.setPadding(0,dp(20),0,dp(8));
        form.addView(heading);
        Switch enabled = new Switch(this);
        enabled.setText("Show overlay");
        enabled.setChecked(config.enabled);
        form.addView(enabled);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        form.addView(details);
        details.setVisibility(config.enabled ? View.VISIBLE : View.GONE);
        Runnable refresh = () -> {
            details.setVisibility(config.enabled ? View.VISIBLE : View.GONE);
            PerformanceOverlay.preview(preview,config);
        };
        enabled.setOnCheckedChangeListener((button,checked)->{config.enabled=checked;refresh.run();});
        for(int i=0;i<PerformanceOverlay.KEYS.length;i++){
            final int index=i;
            CheckBox item=new CheckBox(this);
            item.setText(PerformanceOverlay.LABELS[i]);
            item.setChecked(config.metrics[i]);
            details.addView(item);
            item.setOnCheckedChangeListener((button,checked)->{config.metrics[index]=checked;refresh.run();});
        }
        TextView note=new TextView(this);
        note.setText("CPU is this process's usage (100% = one logical core). Frame time is an average, temperature is the battery sensor.");
        note.setTextSize(12);
        details.addView(note);
        Switch background = new Switch(this);
        background.setText("Show text background");
        background.setChecked(config.background);
        details.addView(background);
        background.setOnCheckedChangeListener((button,checked)->{config.background=checked;refresh.run();});
        TextView sizeLabel=new TextView(this);
        sizeLabel.setText("Text size: "+config.textSize+" sp");
        sizeLabel.setPadding(0,dp(12),0,0);
        details.addView(sizeLabel);
        SeekBar size=new SeekBar(this);
        size.setMax(PerformanceOverlay.MAX_TEXT_SIZE-PerformanceOverlay.MIN_TEXT_SIZE);
        size.setProgress(config.textSize-PerformanceOverlay.MIN_TEXT_SIZE);
        details.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                config.textSize=progress+PerformanceOverlay.MIN_TEXT_SIZE;sizeLabel.setText("Text size: "+config.textSize+" sp");refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        TextView positionLabel=new TextView(this);
        positionLabel.setText("Text position");
        positionLabel.setPadding(0,dp(12),0,0);
        details.addView(positionLabel);
        Spinner position=new Spinner(this);
        ArrayAdapter<String> positions=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,PerformanceOverlay.POSITIONS);
        positions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        position.setAdapter(positions);
        position.setSelection(config.position);
        details.addView(position);
        position.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int index,long id){config.position=index;refresh.run();}
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        refresh.run();
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
        TextView cacheHeading = new TextView(this);
        cacheHeading.setText("Cache allocation (applied on launch)");
        cacheHeading.setTextSize(18);
        cacheHeading.setPadding(0, dp(20), 0, dp(4));
        form.addView(cacheHeading);
        Spinner[] cacheControls = new Spinner[CACHE_KEYS.length];
        for (int i = 0; i < CACHE_KEYS.length; i++) {
            TextView label = new TextView(this);
            label.setText(CACHE_LABELS[i]);
            label.setPadding(0, dp(12), 0, 0);
            form.addView(label);
            String[] values = CACHE_VALUES[i];
            String[] labels = values.clone();
            labels[0] = "Module default";
            if (i == 2) labels[1] = "Disabled";
            Spinner control = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            control.setAdapter(adapter);
            String saved = prefs.getString(CACHE_PREFIX + CACHE_KEYS[i], "");
            for (int j = 0; j < values.length; j++) if (values[j].equals(saved)) control.setSelection(j);
            cacheControls[i] = control;
            form.addView(control, fullWidthButtonParams());
        }
        PerformanceOverlay.Settings overlayConfig=PerformanceOverlay.Settings.read(prefs);
        FrameLayout previewPanel=new FrameLayout(this);
        previewPanel.setBackgroundColor(0xff202830);
        TextView preview=PerformanceOverlay.makeView(this);
        previewPanel.addView(preview,PerformanceOverlay.layout(this,0));
        LinearLayout overlayForm = new LinearLayout(this);
        overlayForm.setOrientation(LinearLayout.VERTICAL);
        addOverlaySettings(overlayForm,previewPanel,preview,overlayConfig);
        form.addView(overlayForm,0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        LinearLayout content=new LinearLayout(this);
        boolean landscape=getResources().getDisplayMetrics().widthPixels>getResources().getDisplayMetrics().heightPixels;
        content.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        LinearLayout previewColumn=new LinearLayout(this);
        previewColumn.setOrientation(LinearLayout.VERTICAL);
        previewColumn.setPadding(dp(8),dp(8),dp(16),dp(8));
        TextView previewLabel=new TextView(this);
        previewLabel.setText("Overlay preview (sample values)");
        previewLabel.setPadding(0,0,0,dp(8));
        previewColumn.addView(previewLabel);
        previewColumn.addView(previewPanel,new LinearLayout.LayoutParams(-1,0,1));
        if(landscape){
            content.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
            content.addView(previewColumn,new LinearLayout.LayoutParams(0,-1,1));
        }else{
            content.addView(previewColumn,new LinearLayout.LayoutParams(-1,dp(190)));
            content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        }
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xff151719);
        TextView title = new TextView(this);
        title.setText("PV7 launch configuration");
        title.setTextSize(20);
        title.setTextColor(0xffeeeeee);
        title.setPadding(dp(16),dp(8),dp(16),dp(4));
        screen.addView(title);
        screen.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button reset=new Button(this);
        reset.setText("Reset defaults");
        reset.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : FLAG_KEYS) editor.remove(FLAG_PREFIX + key);
            for (String key : CACHE_KEYS) editor.remove(CACHE_PREFIX + key);
            for (String key : prefs.getAll().keySet()) if(key.startsWith(PerformanceOverlay.PREFIX)) editor.remove(key);
            editor.commit();
            dialog.dismiss();
        });
        Button unchanged=new Button(this);
        unchanged.setText("Launch unchanged");
        unchanged.setOnClickListener(v -> dialog.dismiss());
        Button save=new Button(this);
        save.setText("Save & launch");
        save.setOnClickListener(v -> {
            SharedPreferences.Editor editor = prefs.edit();
            for (int i = 0; i < FLAG_KEYS.length; i++) {
                int choice = controls[i].getSelectedItemPosition();
                String key = FLAG_PREFIX + FLAG_KEYS[i];
                if (choice == 0) editor.remove(key);
                else editor.putString(key, choice == 1 ? "true" : "false");
            }
            for (int i = 0; i < CACHE_KEYS.length; i++) {
                int choice = cacheControls[i].getSelectedItemPosition();
                String key = CACHE_PREFIX + CACHE_KEYS[i];
                if (choice <= 0) editor.remove(key);
                else editor.putString(key, CACHE_VALUES[i][choice]);
            }
            overlayConfig.write(editor);
            editor.commit();
            dialog.dismiss();
        });
        for(Button button:new Button[]{reset,unchanged,save}) {
            button.setTextSize(12);
            actions.addView(button,new LinearLayout.LayoutParams(0,-2,1));
        }
        screen.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        dialog.setContentView(screen);
        dialog.setOnDismissListener(d -> {
            flagsOpen = false;
            performanceOverlay.configure(PerformanceOverlay.Settings.read(prefs));
            releaseLaunch.run();
        });
        dialog.show();
        dialog.getWindow().setLayout(-1,-1);
    }

    private void clearStorageBlackWarning() {
        blackFrameProbe.reset();
        if(storageBlackWarning) {
            storageBlackWarning=false;
            if(!showingRecovery && baselineRunning)guestOverlay.setVisibility(View.GONE);
        }
    }

    private void sampleStorageBlackFrame(long now) {
        String hint=GuestStorageDiagnostics.storageMessage(now);
        if(hint==null) {clearStorageBlackWarning();return;}
        if(showingRecovery || blackSamplePending || now<nextBlackSample ||
            !activityResumed || restartingGuest || !baselineRunning || !sawGuestFrame) return;
        nextBlackSample=now+1000;
        android.graphics.Bitmap sample=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);
        final int generation=storageProbeGeneration;
        final Surface surface=renderSurface.getHolder().getSurface();
        blackSamplePending=true;
        try {
            android.view.PixelCopy.request(surface,sample,result -> {
                try {
                    if(generation!=storageProbeGeneration || !activityResumed || restartingGuest ||
                        showingRecovery || !baselineRunning || surfaceNeedsRebind || restartWhenStopped ||
                        !surface.isValid()) return;
                    long time=SystemClock.elapsedRealtime();
                    String evidence=GuestStorageDiagnostics.storageMessage(time);
                    if(evidence==null){clearStorageBlackWarning();return;}
                    if(result!=android.view.PixelCopy.SUCCESS){blackFrameProbe.reset();return;}
                    int[] pixels=new int[64*64];
                    sample.getPixels(pixels,0,64,0,0,64,64);
                    boolean black=GuestBlackFrameProbe.isBlack(pixels);
                    if(!black){clearStorageBlackWarning();return;}
                    if(!blackFrameProbe.observe(true,true,time))return;
                    storageBlackWarning=true;
                    guestOverlay.setVisibility(View.VISIBLE);
                    guestStatus.setText("Guest is running but the screen is black\n\n"+evidence);
                    guestStatus.setVisibility(View.VISIBLE);
                    guestSpinner.setVisibility(View.GONE);
                    guestAction.setText("Restart guest");
                    guestAction.setVisibility(View.VISIBLE);
                    guestAction.setOnClickListener(v -> restartGuestProcess("missing expansion recovery"));
                } finally {
                    sample.recycle();
                    if(generation==storageProbeGeneration)blackSamplePending=false;
                }
            },supervisor);
        } catch(RuntimeException unavailable) {
            sample.recycle();blackSamplePending=false;blackFrameProbe.reset();
        }
    }

    private void showLostGuest() {
        if (restartingGuest || showingRecovery) return;
        showingRecovery = true;
        storageBlackWarning=false;
        blackFrameProbe.reset();
        startupStageCaption.setVisibility(View.GONE);
        if(performanceOverlay!=null)performanceOverlay.stop();
        String storageHint=GuestStorageDiagnostics.message(SystemClock.elapsedRealtime());
        if (storageHint==null && sawGuestFrame && recoveryBanner != null) {
            recoveryBanner.setVisibility(View.VISIBLE);
            return;
        }
        guestOverlay.setVisibility(View.VISIBLE);
        guestStatus.setText("Lost connection to the guest"+(storageHint==null?"":"\n\n"+storageHint));
        guestStatus.setVisibility(View.VISIBLE);
        guestSpinner.setVisibility(View.VISIBLE);
        guestAction.setText("Restart guest");
        guestAction.setVisibility(View.VISIBLE);
        guestAction.setOnClickListener(v -> restartGuestProcess("manual recovery"));
    }

    private void pollGuest() {
        if (restartRequestAvailable && launchReady) {
            try {
                if (!cocosBackend && nativeConsumeRestartRequest()) {
                    restartGuestProcess("guest restart request");
                    return;
                }
            } catch (UnsatisfiedLinkError unavailable) { restartRequestAvailable = false; }
        }
        if (!launchReady || !compatConfigured) return;
        if (renderSurface == null || !renderSurface.getHolder().getSurface().isValid() || restartWhenStopped || surfaceNeedsRebind) {
            lastGuestProgress = SystemClock.elapsedRealtime();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        showCompatibilityNotice(now);
        if (heartbeatAvailable && baselineRunning) {
            try {
                long count = cocosBackend ? CocosGuestRuntime.frames() : nativeGuestFrameCount();
                long present = lastGuestPresentCount;
                if (extendedHeartbeat) {
                    try { present = cocosBackend ? CocosGuestRuntime.presents() : nativeGuestPresentCount(); }
                    catch (UnsatisfiedLinkError unavailable) { extendedHeartbeat = false; }
                }
                if (count > 0) guestLoopReached = true;
                if (count != lastGuestFrameCount || present != lastGuestPresentCount) {
                    lastGuestFrameCount = count;
                    lastGuestPresentCount = present;
                    lastGuestProgress = now;
                    sawGuestFrame = true;
                    showingRecovery = false;
                    recoveryBanner.setVisibility(View.GONE);
                    if(!storageBlackWarning)guestOverlay.setVisibility(View.GONE);
                }
                sampleStorageBlackFrame(now);
                performanceOverlay.update(count,gameBufferWidth,gameBufferHeight,guestLoopReached&&!showingRecovery);
            } catch (UnsatisfiedLinkError unavailable) {
                heartbeatAvailable = false;
                guestOverlay.setVisibility(View.GONE);
                Log.w("PV7HOST", "Guest heartbeat unavailable in this native module");
            }
        }
        if (!guestLoopReached && !showingRecovery) {
            String stage = javaStartupStage;
            if (baselineRunning && nativeStagesAvailable) {
                try { stage = cocosBackend ? cocosStage : nativeGuestStartupStage(); }
                catch (UnsatisfiedLinkError unavailable) { nativeStagesAvailable = false; }
            }
            guestStatus.setText(stage+"...");
            startupStageCaption.setText(stage+"...");
            startupStageCaption.setVisibility(sawGuestFrame?View.VISIBLE:View.GONE);
        } else {
            startupStageCaption.setVisibility(View.GONE);
        }
        if (lastGuestProgress == 0L) lastGuestProgress = now;
        if (guestPreparationFailed || (!baselineRunning && !guestPreparationRunning && !restartWhenStopped && sawGuestFrame)) {
            showLostGuest();
        } else if (heartbeatAvailable && now - lastGuestProgress >=
                   (guestLoopReached ? GUEST_STALL_MS : STARTUP_TIMEOUT_MS)) {
            showLostGuest();
        }
    }

    private void restartGuestProcess(String reason) {
        if (restartingGuest || isFinishing()) return;
        restartingGuest = true;
        startupStageCaption.setVisibility(View.GONE);
        if(recoveryBanner!=null)recoveryBanner.setVisibility(View.GONE);
        if(performanceOverlay!=null)performanceOverlay.stop();
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
            int orientation = getRequestedOrientation();
            if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                orientation = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            }
            intent.putExtra("pv7.guestOrientation", orientation);
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

        if (cocosBackend) { gameBufferWidth=480; gameBufferHeight=320; }
        else if (viewWidth >= viewHeight) {
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
        cocosBackend = "cocos2dx".equals(hostProperties.getProperty("guest.engine"));
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
        if (Build.VERSION.SDK_INT >= 34)
            renderSurface.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
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
                if ((!sawGuestFrame && heartbeatAvailable) || restartingGuest || pointerCount <= 0) return true;
                if (cocosBackend) { CocosGuestRuntime.touch(event,renderSurface.getWidth(),renderSurface.getHeight()); return true; }
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
                if (!baselineRunning) baselineStarted = false;
                if (baselineRunning && !surfaceRebindAvailable) restartWhenStopped = true;
                // Wait for surfaceChanged so the fixed buffer can be matched to
                // the actual laid-out SurfaceView aspect ratio before Unity starts.
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Rect frame = holder.getSurfaceFrame();
                Log.i("PV7HOST", "PV7LIFE surfaceChanged view=" + renderSurface.getWidth() + "x" + renderSurface.getHeight()
                        + " buffer=" + frame.width() + "x" + frame.height());
                if (baselineRunning && surfaceNeedsRebind) {
                    if (!configureGameBufferForView()) return;
                    if (setGuestHostSurface(holder.getSurface())) {
                        surfaceNeedsRebind = false;
                        restartWhenStopped = false;
                        lastGuestProgress = SystemClock.elapsedRealtime();
                        setGuestBackgrounded(!activityResumed);
                    }
                    return;
                }
                if (baselineRunning) return;
                if (!configureGameBufferForView()) return;
                if (activityResumed && !baselineRunning) {
                    baselineStarted = false;
                    startBaselineOnce();
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i("PV7HOST", "PV7LIFE surfaceDestroyed running=" + baselineRunning + " resumed=" + activityResumed);
                // A surface loss is not a guest exit. Pause until the replacement
                // SurfaceView window can be rebound on the native owner thread.
                if (baselineRunning && !isFinishing() && !restartingGuest && setGuestHostSurface(null)) {
                    surfaceNeedsRebind = true;
                    setGuestBackgrounded(true);
                    return;
                }
                restartWhenStopped = true;
                baselineStarted = false;
                setEngineRunning(false);
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
        updateSupervisorNotification(false);
        setGuestBackgrounded(surfaceNeedsRebind);
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
        if (!isFinishing()) updateSupervisorNotification(true);
        setGuestBackgrounded(true);
        supervisor.removeCallbacks(guestWatchdog);
        if(performanceOverlay!=null)performanceOverlay.stop();
        // Do not tear the translated Unity runtime down merely because another
        // Activity covered us. If Android actually destroys the Surface,
        // surfaceDestroyed() performs the stop/restart handoff.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        supervisor.removeCallbacksAndMessages(null);
        if(performanceOverlay!=null)performanceOverlay.close();
        clearSupervisorNotification();
        restartWhenStopped = false;
        setEngineRunning(false);
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
        for (int i = 0; i < CACHE_KEYS.length; i++) {
            String value = getPreferencesStore().getString(CACHE_PREFIX + CACHE_KEYS[i], "");
            for (int j = 1; j < CACHE_VALUES[i].length; j++) {
                if (CACHE_VALUES[i][j].equals(value)) {
                    config.append("property.pv7.cache.").append(CACHE_KEYS[i]).append('=').append(value).append('\n');
                    break;
                }
            }
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
            GuestStorageDiagnostics.reset();
            GuestCompatibilityEvents.reset();
            storageProbeGeneration++;
            blackSamplePending=false;
            storageBlackWarning=false;
            nextBlackSample=0;
            blackFrameProbe.reset();
            surfaceNeedsRebind = false;
            restartWhenStopped = false;
            if (!compatConfigured) {
                if (!cocosBackend) configureCompatibility();
                compatConfigured = true;
            }
            lastGuestProgress = SystemClock.elapsedRealtime();
            lastGuestFrameCount = 0;
            lastGuestPresentCount = 0;
            guestLoopReached = false;
            sawGuestFrame = false;
            setGuestBackgrounded(false);
            setJavaStartupStage("Preparing guest");
            showingRecovery = false;
        }
        // The old code executed ~200 MiB of guest APK extraction, bootstrap
        // hashing and legacy-mod migration synchronously on the UI thread.
        // This blocked Android input/redraw and delayed Unity/mod startup.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if(cocosBackend) prepareAndRunCocos(); else prepareGuestsAndRunSafeBaseline();
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
            setJavaStartupStage("Extracting guest assets");
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

    private void prepareAndRunCocos() {
        boolean started = false;
        try {
            setJavaStartupStage("Preparing Cocos runtime");
            CocosGuestRuntime.load();
            String guestPackage = hostProperties.getProperty("guest.package","");
            int version = Integer.parseInt(hostProperties.getProperty("guest.versionCode","0"));
            CocosGuestRuntime.prepare(this,guestPackage,version);
            File library = prepareGuestAsset("guest/libgame.so","pv7-guest-libgame.so");
            final Surface surface = renderSurface.getHolder().getSurface();
            synchronized (baselineLock) {
                if (!activityResumed || restartWhenStopped || surface == null || !surface.isValid()) return;
                CocosGuestRuntime.reset(Boolean.parseBoolean(hostProperties.getProperty("guest.bestPark","false")));
                baselineRunning = true;
                baselineThread = Thread.currentThread();
                started = true;
            }
            cocosStage = "Starting Cocos translator";
            File report=new File(getFilesDir(),"pv7-cocos-runtime.log");
            int exit = CocosGuestRuntime.run(library,report,surface);
            if(exit!=0) {
                try(java.io.RandomAccessFile log=new java.io.RandomAccessFile(report,"r")) {
                    long offset=Math.max(0,log.length()-16384);
                    log.seek(offset);
                    byte[] tail=new byte[(int)(log.length()-offset)];
                    log.readFully(tail);
                    String detail=new String(tail,java.nio.charset.StandardCharsets.UTF_8);
                    String fault=GuestCompatibilityEvents.extractFault(detail);
                    if(fault!=null) {
                        if(fault.startsWith("JNI_") || GuestStorageDiagnostics.storageMessage(SystemClock.elapsedRealtime())==null)
                            GuestStorageDiagnostics.explicitRuntimeFault(fault);
                        GuestCompatibilityEvents.Event event=GuestCompatibilityEvents.report("Cocos2d-x",fault,
                            "Guest stopped safely; this operation cannot be emulated yet.",true);
                        if(event!=null)persistCompatibilityEvent(this,event);
                    }
                } catch(java.io.IOException ignored) {}
            }
            if (exit != 0 || (activityResumed && !restartWhenStopped && !restartingGuest && !isFinishing()))
                guestPreparationFailed = true;
            Log.i("PV7HOST","PV7 Cocos backend exit="+exit);
        } catch (Throwable failure) {
            guestPreparationFailed=true;
            Log.e("PV7HOST","Cocos preparation/runtime failed",failure);
        } finally {
            if (started) synchronized (baselineLock) {
                baselineThread=null;
                baselineRunning=false;
            }
        }
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
            setJavaStartupStage("Preparing guest mods");
            migrateLegacyCnrModsIfAvailable();
            seedBootstrapFiles();
            CNRModsDocumentsProvider.notifyRootChanged(this);
            setJavaStartupStage("Checking cached guest assets");
            prepareGuestFilesCached();
            if (activityResumed && !restartWhenStopped) { setJavaStartupStage("Starting translator"); runSafeBaseline(); }
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
                            setEngineRunning(false);
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
            setEngineRunning(true);
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
