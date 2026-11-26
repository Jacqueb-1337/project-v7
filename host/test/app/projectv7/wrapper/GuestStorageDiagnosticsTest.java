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
        System.out.println("10 storage diagnostics checks passed");
    }
}
