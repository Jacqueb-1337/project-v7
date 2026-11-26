package app.projectv7.wrapper;
public final class GuestStorageDiagnosticsTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        GuestStorageDiagnostics.reset();
        check(GuestStorageDiagnostics.message(100)==null);
        GuestStorageDiagnostics.report("optional.png",false,"missing",100);
        check(GuestStorageDiagnostics.message(101)==null);
        GuestStorageDiagnostics.report("/obb/main.16.example.obb",false,"missing",100);
        check(GuestStorageDiagnostics.message(101).contains("main.16.example.obb"));
        check(GuestStorageDiagnostics.message(101).contains("could not be found"));
        GuestStorageDiagnostics.report("main.16.example.obb",true,"",102);
        check(GuestStorageDiagnostics.message(103)==null);
        GuestStorageDiagnostics.report("patch.16.example.obb",false,"corrupt",200);
        check(GuestStorageDiagnostics.message(201).contains("appears corrupt"));
        GuestStorageDiagnostics.explicitRuntimeFault("unresolved JNI class");
        check(GuestStorageDiagnostics.message(201).contains("compatibility error"));
        GuestStorageDiagnostics.reset();
        GuestStorageDiagnostics.report("main.16.example.obb",false,"unreadable",0);
        check(GuestStorageDiagnostics.message(1).contains("could not be read"));
        check(GuestStorageDiagnostics.message(120001)==null);
        GuestStorageDiagnostics.reset();
        check(GuestStorageDiagnostics.message(1)==null);
        GuestBlackFrameProbe black=new GuestBlackFrameProbe();
        check(!black.observe(false,true,0));
        check(!black.observe(true,true,1000));
        check(!black.observe(true,true,2000));
        check(!black.observe(true,true,3000));
        check(black.observe(true,true,4000));
        check(!black.observe(true,false,5000));
        check(!black.observe(true,true,6000));
        check(!black.observe(false,true,10000));
        check(GuestBlackFrameProbe.isBlack(new int[]{0xff000000,0x00000000}));
        check(!GuestBlackFrameProbe.isBlack(new int[]{0xff000000,0xff010000}));
        check(!GuestBlackFrameProbe.isBlack(new int[0]));
        System.out.println("21 storage and black-frame diagnostics checks passed");
    }
}
