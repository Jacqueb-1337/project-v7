package app.projectv7.wrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Process-local, bounded evidence shared by all engine adapters. */
public final class GuestStorageDiagnostics {
    private static final long MAX_AGE_MS=120000;
    private static final int MAX_RECORDS=16;
    private static final Map<String, Failure> failures=new LinkedHashMap<>();
    private static String runtimeFault;
    static final class Failure {
        final String name,reason; final long time;
        Failure(String name,String reason,long time){this.name=name;this.reason=reason;this.time=time;}
    }
    private GuestStorageDiagnostics(){}
    public static synchronized void reset(){failures.clear();runtimeFault=null;}
    private static String name(String path){
        if(path==null)return "";
        String s=path.replace('\\','/');
        s=s.substring(s.lastIndexOf('/')+1);
        return s.length()<=180 && s.toLowerCase(Locale.ROOT).endsWith(".obb")
            && s.matches("[A-Za-z0-9._-]+")?s:"";
    }
    /** Call only after an actual guest request; a directory scan is not evidence. */
    public static synchronized void report(String path,boolean success,String reason,long now){
        String key=name(path);if(key.isEmpty())return;
        failures.remove(key);
        if(success)return;
        if(!"missing".equals(reason)&&!"unreadable".equals(reason)&&!"corrupt".equals(reason))return;
        failures.put(key,new Failure(key,reason,now));
        while(failures.size()>MAX_RECORDS)failures.remove(failures.keySet().iterator().next());
    }
    public static synchronized void explicitRuntimeFault(String reason){runtimeFault=reason;}
    public static synchronized String message(long now){
        if(runtimeFault!=null)return "Guest runtime compatibility error: "+runtimeFault;
        return storageMessage(now);
    }
    static synchronized Failure latestFailure(long now){
        if(runtimeFault!=null)return null;
        Failure last=null;
        for(Failure f:failures.values())
            if(now>=f.time && now-f.time<=MAX_AGE_MS)last=f;
        return last;
    }
    public static synchronized String storageMessage(long now){
        Failure last=latestFailure(now);
        if(last==null)return null;
        String detail="missing".equals(last.reason)?"could not be found":
            "corrupt".equals(last.reason)?"appears corrupt":"could not be read";
        return "Possible missing or invalid game data\nRequested OBB: "+last.name+
            "\nThe file "+detail+". Check PV7 Game Storage / obb, then restart the guest.";
    }
}
