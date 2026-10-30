package app.projectv7.wrapper;

import android.content.Context;
import java.io.File;

/**
 * Context-aware entrypoint for PV7's generic guest filesystem virtualizer.
 * The native/JNI compatibility bridge should call rewriteReturnedPath before
 * exposing Java paths to ARM32 callers, and rewriteOpenPath before POSIX I/O.
 */
public final class Pv7GuestStorageBridge {
    private Pv7GuestStorageBridge() {}

    public static String root(Context context) {
        return new File(context.getFilesDir(), "GuestSharedStorage").getAbsolutePath();
    }

    public static void ensureFolders(Context context) {
        String root = root(context);
        for (String category : new String[] {"obb","data","cache","files"}) {
            File directory = new File(root, category);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not prepare PV7 guest storage " + category);
            }
        }
    }

    public static String rewriteReturnedPath(Context context, String originalPackage,
                                             String guestValue) {
        return Pv7GuestStoragePaths.rewrite(guestValue, originalPackage, root(context));
    }

    public static String rewriteOpenPath(Context context, String originalPackage,
                                         String requestedPath) {
        return Pv7GuestStoragePaths.rewrite(requestedPath, originalPackage, root(context));
    }

    public static String guestObbDirectory(Context context) {
        return Pv7GuestStoragePaths.obbDirectory(root(context));
    }

    public static String guestExpansionFile(Context context, String originalPackage,
                                            int versionCode) {
        return Pv7GuestStoragePaths.expansionFile(root(context), originalPackage, versionCode);
    }
}
