package app.projectv7.wrapper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;

final class GuestStorageRecoveryCard extends LinearLayout {
    private final LinearLayout header,details;
    private final TextView title,summary,arrow,filename,status,importButton,storageButton,restartButton;
    private final ScrollView scroll;
    private final ProgressBar progress;
    private ValueAnimator animator;
    private boolean expanded=false;
    private String expected="";
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);return t;
    }
    private LayoutParams row(int margin){
        LayoutParams l=new LayoutParams(-1,-2);l.topMargin=dp(margin);return l;
    }
    private TextView action(String label,int fill,int stroke,Runnable handler){
        TextView t=text(label,15,0xfffafafa,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(48));
        t.setPadding(dp(12),dp(10),dp(12),dp(10));t.setBackground(shape(fill,stroke,12));
        t.setFocusable(true);t.setClickable(true);t.setOnClickListener(v->handler.run());return t;
    }
    GuestStorageRecoveryCard(Context ctx,Runnable imp,Runnable storage,Runnable restart){
        super(ctx);setOrientation(VERTICAL);setPadding(dp(16),dp(12),dp(16),dp(12));
        setBackground(shape(0xff1c1c1e,0xff353535,18));setElevation(dp(8));setClickable(true);
        header=new LinearLayout(ctx);header.setGravity(Gravity.CENTER_VERTICAL);header.setOrientation(HORIZONTAL);
        TextView mark=text("\u26A0",24,0xffe9883b,true);mark.setGravity(Gravity.CENTER);header.addView(mark,new LayoutParams(dp(32),dp(40)));
        LinearLayout headings=new LinearLayout(ctx);headings.setOrientation(VERTICAL);
        LayoutParams hp=new LayoutParams(0,-2,1);hp.leftMargin=dp(10);header.addView(headings,hp);
        title=text("Missing game data",19,0xfffafafa,true);headings.addView(title);
        summary=text("",13,0xffc6c6c8,false);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);headings.addView(summary,row(4));
        arrow=text("\u2303",24,0xffb5b5b8,true);arrow.setGravity(Gravity.CENTER);
        header.addView(arrow,new LayoutParams(dp(40),dp(40)));addView(header);
        header.setFocusable(true);header.setClickable(true);header.setOnClickListener(v->expand(!expanded,true));
        scroll=new ScrollView(ctx);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        details=new LinearLayout(ctx);details.setOrientation(VERTICAL);details.setPadding(0,dp(14),0,0);
        details.addView(text("This game requires an OBB expansion file to run.",15,0xfff2f2f2,false));
        LinearLayout file=new LinearLayout(ctx);file.setOrientation(VERTICAL);file.setPadding(dp(12),dp(12),dp(12),dp(12));
        file.setBackground(shape(0xff252527,0,10));file.addView(text("Required file",12,0xffb5b5b8,false));
        filename=text("",14,0xfff4f4f4,true);filename.setTextIsSelectable(true);file.addView(filename,row(4));details.addView(file,row(12));
        details.addView(text("PV7 uses its own game storage instead of Android/obb. Select your original OBB file. The imported copy will automatically be renamed to the required filename.",14,0xffededee,false),row(12));
        importButton=action("Import OBB file",0xff3567c9,0,imp);details.addView(importButton,row(14));
        storageButton=action("Open game storage",0xff1c1c1e,0xff3a3a3c,storage);details.addView(storageButton,row(9));
        progress=new ProgressBar(ctx,null,android.R.attr.progressBarStyleHorizontal);progress.setVisibility(GONE);details.addView(progress,row(10));
        status=text("",13,0xffc7c7ca,false);status.setVisibility(GONE);details.addView(status,row(6));
        restartButton=action("Restart guest",0,0,restart);restartButton.setTextColor(0xffb5b5b8);details.addView(restartButton,row(3));
        scroll.addView(details,new ScrollView.LayoutParams(-1,-2));addView(scroll,new LayoutParams(-1,0));scroll.setVisibility(GONE);setVisibility(GONE);
    }
    String expected(){return expected;}
    void showFailure(GuestStorageDiagnostics.Failure f){
        if(f==null)return;
        if(expected.equals(f.name)&&getVisibility()==VISIBLE)return;
        expected=f.name;filename.setText(expected);
        title.setText("missing".equals(f.reason)?"Missing game data":"Invalid game data");
        summary.setText(("missing".equals(f.reason)?"Required OBB is missing: ":"Required OBB cannot be read: ")+expected);
        status.setVisibility(GONE);progress.setVisibility(GONE);importButton.setText("Import OBB file");
        setVisibility(VISIBLE);expand(false,false);
    }
    void hideCard(){if(animator!=null)animator.cancel();setVisibility(GONE);expected="";expanded=false;}
    private void expand(boolean open,boolean animate){
        if(animator!=null)animator.cancel();expanded=open;
        header.setContentDescription(title.getText()+". "+summary.getText()+". "+(open?"Collapse":"Expand")+" details");
        arrow.animate().cancel();if(animate)arrow.animate().rotation(open?180:0).setDuration(240).start();else arrow.setRotation(open?180:0);
        if(open)scroll.setVisibility(VISIBLE);
        int width=Math.max(dp(180),getWidth()-getPaddingLeft()-getPaddingRight());
        details.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        int parent=getParent() instanceof View?((View)getParent()).getHeight():getResources().getDisplayMetrics().heightPixels;
        int cap=Math.max(dp(72),parent-header.getMeasuredHeight()-getPaddingTop()-getPaddingBottom()-dp(32));
        int end=open?Math.min(cap,details.getMeasuredHeight()):0;
        if(!animate){resize(end);scroll.setVisibility(open?VISIBLE:GONE);return;}
        animator=ValueAnimator.ofInt(scroll.getLayoutParams().height,end);animator.setDuration(open?320:240);
        animator.setInterpolator(new PathInterpolator(.2f,0f,0f,1f));
        animator.addUpdateListener(v->{resize((Integer)v.getAnimatedValue());if(!expanded&&scroll.getLayoutParams().height==0)scroll.setVisibility(GONE);});
        animator.start();
    }
    private void resize(int h){scroll.getLayoutParams().height=h;scroll.requestLayout();}
    void importing(long bytes,long total){
        importButton.setEnabled(false);storageButton.setEnabled(false);restartButton.setEnabled(false);
        boolean first=progress.getVisibility()!=VISIBLE;
        progress.setVisibility(VISIBLE);progress.setIndeterminate(total<=0);
        if(total>0){progress.setMax(100);progress.setProgress((int)Math.min(100,bytes*100.0/total));}
        status.setVisibility(VISIBLE);status.setTextColor(0xffc7c7ca);
        status.setText("Importing "+(bytes/1048576)+" MB"+(total>0?" of "+(total/1048576)+" MB":"")+"...");
        if(first)expand(true,true);
    }
    void result(boolean success,String message){
        progress.setVisibility(GONE);status.setVisibility(VISIBLE);status.setText(message);
        status.setTextColor(success?0xff92d8a3:0xffffb199);importButton.setEnabled(true);storageButton.setEnabled(true);restartButton.setEnabled(true);
        if(success){title.setText("Game data imported");summary.setText("Restart the guest to load "+expected);importButton.setText("Import another OBB");}
        expand(true,true);
    }
}
