package app.projectv7.wrapper;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;

final class GuestCompatibilityCard extends LinearLayout {
    private final LinearLayout header,details;
    private final TextView title,summary,arrow,method,fallback;
    private final ScrollView scroll;
    private final CheckBox remember;
    private Runnable approve, reject, copy;
    private GuestCompatibilityDecision active;
    private ValueAnimator animator;
    private boolean expanded=false;

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
    GuestCompatibilityCard(Context ctx){
        super(ctx);setOrientation(VERTICAL);setPadding(dp(16),dp(12),dp(16),dp(12));
        setBackground(shape(0xff1c1c1e,0xff353535,18));setElevation(dp(8));setClickable(true);
        header=new LinearLayout(ctx);header.setGravity(Gravity.CENTER_VERTICAL);header.setOrientation(HORIZONTAL);
        TextView mark=text("\u26A0",24,0xffe9883b,true);mark.setGravity(Gravity.CENTER);header.addView(mark,new LayoutParams(dp(32),dp(40)));
        LinearLayout headings=new LinearLayout(ctx);headings.setOrientation(VERTICAL);
        LayoutParams hp=new LayoutParams(0,-2,1);hp.leftMargin=dp(10);header.addView(headings,hp);
        title=text("Compatibility interruption",19,0xfffafafa,true);headings.addView(title);
        summary=text("",13,0xffc6c6c8,false);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);headings.addView(summary,row(4));
        arrow=text("\u2303",24,0xffb5b5b8,true);arrow.setGravity(Gravity.CENTER);
        header.addView(arrow,new LayoutParams(dp(40),dp(40)));addView(header);
        header.setFocusable(true);header.setClickable(true);header.setOnClickListener(v->expand(!expanded,true));
        scroll=new ScrollView(ctx);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);
        details=new LinearLayout(ctx);details.setOrientation(VERTICAL);details.setPadding(0,dp(14),0,0);
        details.addView(text("Guest execution is paused. Continuing may cause unexpected game behavior.",14,0xffededee,false));
        LinearLayout info=new LinearLayout(ctx);info.setOrientation(VERTICAL);info.setPadding(dp(12),dp(12),dp(12),dp(12));info.setBackground(shape(0xff252527,0,10));
        info.addView(text("Unresolved JNI method",12,0xffb5b5b8,false));
        method=text("",14,0xfff4f4f4,true);method.setTextIsSelectable(true);info.addView(method,row(4));
        fallback=text("",14,0xfff4f4f4,false);info.addView(fallback,row(8));details.addView(info,row(12));
        remember=new CheckBox(ctx);remember.setText("Remember for this guest session");remember.setTextColor(0xffeeeeee);details.addView(remember,row(8));
        details.addView(action("Continue anyway",0xff3567c9,0,()->{if(approve!=null)approve.run();}),row(10));
        details.addView(action("Stop guest",0xff1c1c1e,0xff3a3a3c,()->{if(reject!=null)reject.run();}),row(8));
        details.addView(action("Copy error report",0,0,()->{if(copy!=null)copy.run();}),row(4));
        scroll.addView(details,new ScrollView.LayoutParams(-1,-2));addView(scroll,new LayoutParams(-1,0));scroll.setVisibility(GONE);setVisibility(GONE);
    }
    boolean remember(){return remember.isChecked();}
    void showDecision(GuestCompatibilityDecision d,Runnable yes,Runnable no,Runnable report){
        active=d;approve=yes;reject=no;copy=report;remember.setChecked(false);
        method.setText(d.signature);fallback.setText("Proposed fallback: "+d.fallback);
        summary.setText("Guest paused. Tap to review the unsupported method.");
        setVisibility(VISIBLE);bringToFront();expand(false,false);
    }
    void hideDecision(GuestCompatibilityDecision d){
        if(active!=d)return;
        if(animator!=null)animator.cancel();arrow.animate().cancel();
        active=null;approve=null;reject=null;copy=null;setVisibility(GONE);expanded=false;
    }
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
}
