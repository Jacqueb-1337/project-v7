package app.projectv7.wrapper;
public final class GuestCompatibilityEventsTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        GuestCompatibilityEvents.reset();
        check(GuestCompatibilityEvents.poll(0)==null);
        check(GuestCompatibilityEvents.report("Cocos","showLeaderboard()V","offline",false)!=null);
        check(GuestCompatibilityEvents.report("Cocos","showLeaderboard()V","offline",false)==null);
        check(GuestCompatibilityEvents.report("Unity","optional()V","offline",false)!=null);
        check(GuestCompatibilityEvents.poll(0).count==2);
        check(GuestCompatibilityEvents.poll(4999)==null);
        check(GuestCompatibilityEvents.poll(5000).backend.equals("Unity"));
        check(GuestCompatibilityEvents.extractFault("COCOS_FAULT_DETAIL pc=1 reason=JNI_GetStaticMethodID_unknown:abc()V").equals("JNI_GetStaticMethodID_unknown:abc()V"));
        check(GuestCompatibilityEvents.extractFault("nothing")==null);
        GuestCompatibilityEvents.reset();
        for(int i=0;i<64;i++)check(GuestCompatibilityEvents.report("test","method"+i,"offline",false)!=null);
        check(GuestCompatibilityEvents.report("test","over limit","offline",false)==null);
        check(GuestCompatibilityEvents.report("test","fatal","unsafe",true).critical);
        check(GuestCompatibilityEvents.poll(0).signature.equals("fatal"));
        GuestCompatibilityEvents.reset();
        check(GuestCompatibilityEvents.report("test","method(Ljava/lang/String;)V","test",false).signature.equals("method(Ljava/lang/String;)V"));
        check(GuestCompatibilityEvents.report("test","line\nbreak","test",false).signature.equals("line break"));
        check(GuestCompatibilityEvents.report("test","fatal","unsafe",true).critical);
        System.out.println("79 compatibility event checks passed");
    }
}
