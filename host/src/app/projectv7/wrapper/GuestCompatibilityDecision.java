package app.projectv7.wrapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class GuestCompatibilityDecision {
    private final AtomicInteger result=new AtomicInteger(0);
    private final CountDownLatch ready=new CountDownLatch(1);
    final String signature, fallback;
    GuestCompatibilityDecision(String signature,String fallback){
        this.signature=signature;this.fallback=fallback;
    }
    boolean pending(){return result.get()==0;}
    void resolve(boolean allow){if(result.compareAndSet(0,allow?1:2))ready.countDown();}
    boolean await(long millis){
        try{if(!ready.await(millis,TimeUnit.MILLISECONDS))resolve(false);}
        catch(InterruptedException e){resolve(false);Thread.currentThread().interrupt();}
        return result.get()==1;
    }
    static String fallback(String name,String sig){
        if(name==null||sig==null||name.length()>128||sig.length()>256||
            !name.matches("[A-Za-z_$][A-Za-z0-9_$]*"))return null;
        String type="(?:[ZBCSIJFD]|L[A-Za-z0-9_$/]+;)";
        if(!sig.matches("\\((?:\\[*"+type+")*\\)(?:V|\\[*"+type+")"))return null;
        String ret=sig.substring(sig.indexOf(')')+1);
        switch(ret.charAt(0)){
            case 'V':return "Skip call (void)";
            case 'Z':return "false";
            case 'L':case '[':return "null";
            case 'B':case 'C':case 'S':case 'I':return "0";
            default:return null;
        }
    }
}