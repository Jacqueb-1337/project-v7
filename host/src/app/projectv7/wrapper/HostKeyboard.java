package app.projectv7.wrapper;
import android.app.*;
import android.widget.EditText;
import android.text.*;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
public final class HostKeyboard {
    private static WeakReference<Activity> activity=new WeakReference<>(null);
    private static AlertDialog dialog;
    public static void attach(Activity a){activity=new WeakReference<>(a);}
    public static void hide(){Activity a=activity.get();if(a!=null)a.runOnUiThread(()->{if(dialog!=null){dialog.dismiss();dialog=null;}});}
    public static void show(String text,String hint,int keyboard,boolean multiline,boolean secure){
        Activity a=activity.get();if(a==null||a.isFinishing())return;
        a.runOnUiThread(()->{
            if(dialog!=null)dialog.dismiss();
            EditText input=new EditText(a);input.setHint(hint);input.setText(text);input.setSelection(input.length());
            int type=InputType.TYPE_CLASS_TEXT;
            if(keyboard==4)type=InputType.TYPE_CLASS_NUMBER;
            else if(keyboard==5)type=InputType.TYPE_CLASS_PHONE;
            else if(keyboard==7)type|=InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            else if(keyboard==3)type|=InputType.TYPE_TEXT_VARIATION_URI;
            if(secure)type=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD;
            if(multiline)type|=InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            input.setInputType(type);
            input.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){}
                public void afterTextChanged(Editable s){MainActivity.nativeQueueText(s.toString(),false,false);}
            });
            dialog=new AlertDialog.Builder(a).setView(input)
                .setPositiveButton("OK",(d,w)->MainActivity.nativeQueueText(input.getText().toString(),true,false))
                .setNegativeButton("Cancel",(d,w)->MainActivity.nativeQueueText(input.getText().toString(),true,true)).create();
            dialog.setOnCancelListener(d->MainActivity.nativeQueueText(input.getText().toString(),true,true));
            dialog.setOnShowListener(d->{input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});
            dialog.show();
        });
    }
}
