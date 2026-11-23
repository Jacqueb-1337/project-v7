package app.projectv7.wrapper;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Runs in a separate, non-exported process so a deadlocked guest cannot block recovery. */
public final class GuestRestartActivity extends Activity {
    private boolean restartScheduled;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(0xff000000);
        layout.addView(new ProgressBar(this));
        TextView label = new TextView(this);
        label.setTextColor(0xffffffff);
        label.setTextSize(16);
        label.setText("Restarting guest...");
        label.setPadding(0, 24, 0, 0);
        layout.addView(label);
        setContentView(layout);
    }

    @Override protected void onResume() {
        super.onResume();
        if (restartScheduled) return;
        restartScheduled = true;
        final int guestPid = getIntent().getIntExtra("pv7.guestPid", -1);
        // Only a private, same-app caller can launch this Activity.
        if (guestPid > 0 && guestPid != Process.myPid()) Process.killProcess(guestPid);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent launch = new Intent();
            launch.setClassName(getPackageName(), "app.projectv7.wrapper.MainActivity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (Build.VERSION.SDK_INT >= 26) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(getWindowManager().getDefaultDisplay().getDisplayId());
                startActivity(launch, options.toBundle());
            } else startActivity(launch);
            finish();
        }, 500);
    }
}
