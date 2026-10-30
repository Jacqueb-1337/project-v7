package app.projectv7.wrapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Pv7GuestStoragePathsTest {
    private static final String PKG = "com.turner.bestparkintheuniverse";

    private static void check(boolean condition,String description) {
        if(!condition) throw new AssertionError(description);
    }
    private static void mapped(String guest,String expected,String root) {
        String actual=Pv7GuestStoragePaths.rewrite(guest,PKG,root);
        check(new File(actual).equals(new File(root,expected)),
              guest+" mapped to "+actual+" not "+expected);
    }
    public static void main(String[] args) throws Exception {
        Path shared=Files.createTempDirectory("pv7-guest-storage-test");
        String root=shared.toFile().getCanonicalPath();
        mapped("/storage/emulated/0/Android/obb/"+PKG+"/main.16."+PKG+".obb",
               "obb/main.16."+PKG+".obb",root);
        mapped("/sdcard/Android/obb/"+PKG,"obb",root);
        mapped("/mnt/sdcard/Android/obb/"+PKG+"/patch.16."+PKG+".obb",
               "obb/patch.16."+PKG+".obb",root);
        mapped("/Android/obb/"+PKG+"/main.16."+PKG+".obb",
               "obb/main.16."+PKG+".obb",root);
        mapped("/data/data/"+PKG+"/files/save.bin","files/save.bin",root);
        mapped("/data/user/0/"+PKG+"/cache/texture.bin","cache/texture.bin",root);
        mapped("/data/user_de/0/"+PKG+"/data/settings.db","data/settings.db",root);
        mapped("/storage/emulated/0/Android/data/"+PKG+"/files/options.dat",
               "files/options.dat",root);
        mapped("/storage/emulated/0/Android/data/"+PKG+"/cache/tmp.bin",
               "cache/tmp.bin",root);
        mapped("/storage/emulated/0/Android/data/"+PKG+"/data/profile.db",
               "data/profile.db",root);

        String unrelated="/system/etc/hosts";
        check(unrelated.equals(Pv7GuestStoragePaths.rewrite(unrelated,PKG,root)),
              "unrelated system file changed");
        String other="/sdcard/Android/obb/com.other.game/main.1.com.other.game.obb";
        check(other.equals(Pv7GuestStoragePaths.rewrite(other,PKG,root)),
              "other package storage should not be hijacked");
        check(Pv7GuestStoragePaths.rewrite(
              "/sdcard/Android/obb/"+PKG+"/../outside",PKG,root)==null,
              "path traversal must fail closed");
        check(Pv7GuestStoragePaths.rewrite(
              "/sdcard/Android/obb/"+PKG+"/../../etc/passwd",PKG,root)==null,
              "nested path traversal must fail closed");

        File obb=new File(root,"obb");
        check(obb.mkdirs(),"create obb");
        String basename="main.16."+PKG+".obb";
        check(Pv7GuestStoragePaths.expansionFile(root,PKG,16).isEmpty(),
              "missing OBB must return empty, not a fabricated path");
        Path file=new File(obb,basename).toPath();
        Files.write(file,new byte[]{1,2,3});
        check(file.toFile().getCanonicalPath().equals(
              Pv7GuestStoragePaths.expansionFile(root,PKG,16)),
              "existing OBB discovery mismatch");
        check(Pv7GuestStoragePaths.expansionFile(root,PKG,17).isEmpty(),
              "wrong version must not match");
        Files.delete(file);
        Files.delete(obb.toPath());
        Files.delete(shared);
        System.out.println("PV7_GUEST_STORAGE_PATH_TESTS_PASS");
    }
}
