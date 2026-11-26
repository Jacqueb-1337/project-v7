package app.projectv7.wrapper;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent, non-interactive host HUD. No guest hooks beyond the frame counter. */
final class PerformanceOverlay {
    static final String PREFIX = "pv7.overlay.";
    static final int MIN_TEXT_SIZE = 4, MAX_TEXT_SIZE = 32;
    static final String[] KEYS = {"fps", "frameTime", "cpu", "memory", "systemMemory", "temperature", "resolution"};
    static final String[] LABELS = {"FPS", "Average frame time", "Process CPU", "Process RAM (PSS)", "Device available RAM", "Battery temperature", "Render resolution"};
    static final String[] POSITIONS = {"Top left", "Top center", "Top right", "Middle left", "Center", "Middle right", "Bottom left", "Bottom center", "Bottom right"};
    static final int[] GRAVITIES = {
        Gravity.TOP|Gravity.LEFT, Gravity.TOP|Gravity.CENTER_HORIZONTAL, Gravity.TOP|Gravity.RIGHT,
        Gravity.CENTER_VERTICAL|Gravity.LEFT, Gravity.CENTER, Gravity.CENTER_VERTICAL|Gravity.RIGHT,
        Gravity.BOTTOM|Gravity.LEFT, Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM|Gravity.RIGHT
    };

    static final class Settings {
        boolean enabled;
        boolean background = true;
        boolean[] metrics = new boolean[KEYS.length];
        int textSize = 14;
        int position = 0;
        static Settings read(SharedPreferences prefs) {
            Settings s = new Settings();
            s.enabled = prefs.getBoolean(PREFIX+"enabled", false);
            s.background = prefs.getBoolean(PREFIX+"background", true);
            s.textSize = Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, prefs.getInt(PREFIX+"size",14)));
            s.position = Math.max(0, Math.min(POSITIONS.length-1,prefs.getInt(PREFIX+"position",0)));
            for (int i=0; i<KEYS.length; i++) s.metrics[i] = prefs.getBoolean(PREFIX+KEYS[i], i==0 || i==2 || i==3);
            return s;
        }
        void write(SharedPreferences.Editor editor) {
            editor.putBoolean(PREFIX+"enabled",enabled).putBoolean(PREFIX+"background",background).putInt(PREFIX+"size",textSize).putInt(PREFIX+"position",position);
            for(int i=0;i<KEYS.length;i++) editor.putBoolean(PREFIX+KEYS[i],metrics[i]);
        }
        boolean hasMetrics() { for (boolean m : metrics) if(m) return true; return false; }
    }
    private final Context context;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private Settings settings;
    private boolean sampling;
    private boolean active;
    private long lastTime, lastFrames, lastCpu;
    private double fps = Double.NaN, cpu = Double.NaN;
    private String memory = "sampling", systemMemory = "sampling", temperature = "sampling";
    PerformanceOverlay(Context context, FrameLayout parent) {
        this.context=context;
        view=makeView(context);
        view.setVisibility(View.GONE);
        parent.addView(view,layout(context,0));
    }
    static TextView makeView(Context context) {
        TextView v=new TextView(context);
        v.setTextColor(0xfff4f4f4);
        v.setTypeface(Typeface.MONOSPACE);
        v.setIncludeFontPadding(false);
        v.setTextSize(14);
        v.setPadding(dp(context,8),dp(context,5),dp(context,8),dp(context,5));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(0xc0000000);
        bg.setCornerRadius(dp(context,6));
        v.setBackground(bg);
        v.setClickable(false);
        v.setFocusable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return v;
    }
    static int dp(Context c,float n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    static FrameLayout.LayoutParams layout(Context c,int position){
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,GRAVITIES[position]);
        int margin=dp(c,8);
        p.setMargins(margin,margin,margin,margin);
        return p;
    }
    static void render(TextView v, Settings s, String[] values) {
        v.setTextSize(s.textSize);
        // Do not allocate a new background on every metrics refresh.
        boolean hasBackground = v.getBackground() != null;
        if (s.background != hasBackground) {
            if (s.background) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xc0000000);
                bg.setCornerRadius(dp(v.getContext(),6));
                v.setBackground(bg);
            } else v.setBackground(null);
        }
        int horizontal = s.background ? dp(v.getContext(),8) : 0;
        int vertical = s.background ? dp(v.getContext(),5) : 0;
        if (v.getPaddingLeft()!=horizontal || v.getPaddingTop()!=vertical)
            v.setPadding(horizontal,vertical,horizontal,vertical);
        v.setLayoutParams(layout(v.getContext(),s.position));
        v.setGravity((s.position%3==0 ? Gravity.LEFT : s.position%3==1 ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT));
        StringBuilder text=new StringBuilder();
        for(int i=0;i<KEYS.length;i++) if(s.metrics[i]) {
            if(text.length()>0)text.append('\n');
            text.append(values[i]);
        }
        v.setText(text.toString());
        v.setVisibility(s.enabled&&s.hasMetrics()?View.VISIBLE:View.GONE);
    }
    static void preview(TextView v, Settings s) {
        render(v,s,new String[]{"FPS  60.0","Frame  16.7 ms","CPU  82%","RAM  1,024 MiB","Free RAM  3,840 MiB","Battery  34.5 ?C","Render  1024 ? 576"});
    }
    void configure(Settings s) {
        settings=s;
        if(!s.enabled || !s.hasMetrics()) stop();
    }
    void stop(){
        active=false;
        view.setVisibility(View.GONE);
        lastTime=0; lastFrames=0; lastCpu=0;
        fps=Double.NaN;cpu=Double.NaN;
    }
    void close(){
        stop();
        if(worker!=null)worker.shutdownNow();
    }
    void update(long frameCount, int width, int height, boolean visible) {
        if(settings==null || !settings.enabled || !settings.hasMetrics() || !visible) {stop();return;}
        active=true;
        long now=SystemClock.elapsedRealtime();
        long elapsed=now-lastTime;
        if(lastTime==0 || frameCount<lastFrames) {
            lastTime=now;lastFrames=frameCount;lastCpu=Process.getElapsedCpuTime();
            fps=Double.NaN;cpu=Double.NaN;
            show(width,height);
            return;
        }
        if(elapsed<1000)return;
        long cpuNow=Process.getElapsedCpuTime();
        fps=(frameCount-lastFrames)*1000.0/elapsed;
        cpu=(cpuNow-lastCpu)*100.0/elapsed;
        lastTime=now;lastFrames=frameCount;lastCpu=cpuNow;
        show(width,height);
        if(!sampling && (settings.metrics[3] || settings.metrics[4] || settings.metrics[5])) {
            sampling=true;
            final boolean pss=settings.metrics[3], ram=settings.metrics[4], temp=settings.metrics[5];
            if(worker==null)worker=Executors.newSingleThreadExecutor();
            worker.execute(()->{
                String m=memory, r=systemMemory, t=temperature;
                try {
                    if(pss) m=String.format(Locale.US,"%,d MiB",Debug.getPss()/1024);
                    if(ram){
                        ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                        ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();
                        am.getMemoryInfo(mi);
                        r=String.format(Locale.US,"%,d MiB",mi.availMem/1048576);
                    }
                    if(temp){
                        Intent b=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        int value=b==null?Integer.MIN_VALUE:b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE);
                        t=value==Integer.MIN_VALUE?"N/A":String.format(Locale.US,"%.1f ?C",value/10.0);
                    }
                }catch(RuntimeException ignored){}
                final String fm=m,fr=r,ft=t;
                main.post(()->{
                    sampling=false;
                    memory=fm;systemMemory=fr;temperature=ft;
                    if(active)show(width,height);
                });
            });
        }
    }
    private void show(int width,int height){
        if(!active||settings==null)return;
        render(view,settings,new String[]{
            Double.isNaN(fps)?"FPS  sampling":String.format(Locale.US,"FPS  %.1f",fps),
            Double.isNaN(fps)||fps<=0?"Frame  N/A":String.format(Locale.US,"Frame  %.1f ms",1000/fps),
            Double.isNaN(cpu)?"CPU  sampling":String.format(Locale.US,"CPU  %.0f%%",cpu),
            "RAM  "+memory,"Free RAM  "+systemMemory,"Battery  "+temperature,
            String.format(Locale.US,"Render  %d ? %d",width,height)});
    }
}
