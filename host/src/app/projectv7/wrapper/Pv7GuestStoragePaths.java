package app.projectv7.wrapper;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic PV7 guest-path translator. No game or engine names are embedded.
 *
 * Inputs are paths requested by the ORIGINAL game, the original package ID
 * and the generated wrapper's private GuestSharedStorage directory.
 * Unrelated paths are passed through unchanged. Mapped paths are absolute
 * and confined to one of the four shared guest categories.
 */
public final class Pv7GuestStoragePaths {
    private Pv7GuestStoragePaths() {}

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern EXTERNAL = Pattern.compile(
        "^/(?:sdcard|mnt/sdcard|storage/sdcard[0-9]+|storage/emulated/[0-9]+|mnt/shell/emulated/[0-9]+)" +
        "/Android/(obb|data)/([^/]+)(?:/(.*))?$");
    private static final Pattern PRIVATE = Pattern.compile(
        "^/data/(?:data|user/[0-9]+|user_de/[0-9]+)/([^/]+)/(data|cache|files|obb)(?:/(.*))?$");
    private static final Pattern LEGACY_ANDROID = Pattern.compile(
        "^/Android/(obb|data)/([^/]+)(?:/(.*))?$");

    /** Returns the original string unchanged when it is outside the guest namespaces. */
    public static String rewrite(String requested, String guestPackage, String sharedRoot) {
        if (requested == null || requested.isEmpty() || sharedRoot == null ||
            sharedRoot.isEmpty() || !validPackage(guestPackage)) return requested;
        final String path = requested.replace('\\', '/');
        String category = null;
        String remainder = "";
        Matcher external = EXTERNAL.matcher(path);
        if (!external.matches()) external = LEGACY_ANDROID.matcher(path);
        if (external.matches() && guestPackage.equals(external.group(2))) {
            String kind = external.group(1);
            String suffix = external.group(3);
            if ("obb".equals(kind)) {
                category = "obb";
                remainder = suffix == null ? "" : suffix;
            } else if (suffix != null) {
                if (suffix.equals("files") || suffix.startsWith("files/")) {
                    category = "files";
                    remainder = suffix.length() == 5 ? "" : suffix.substring(6);
                } else if (suffix.equals("cache") || suffix.startsWith("cache/")) {
                    category = "cache";
                    remainder = suffix.length() == 5 ? "" : suffix.substring(6);
                } else if (suffix.equals("data") || suffix.startsWith("data/")) {
                    category = "data";
                    remainder = suffix.length() == 4 ? "" : suffix.substring(5);
                }
            }
        } else {
            Matcher internal = PRIVATE.matcher(path);
            if (internal.matches() && guestPackage.equals(internal.group(1))) {
                category = internal.group(2);
                remainder = internal.group(3) == null ? "" : internal.group(3);
            }
        }
        if (category == null) return requested;
        return confine(sharedRoot, category, remainder);
    }

    /** Provide getObbDir() semantics without backing storage at Android/obb. */
    public static String obbDirectory(String sharedRoot) {
        return confine(sharedRoot, "obb", "");
    }

    /** Search only the private OBB folder. Do not synthesize a nonexistent file. */
    public static String expansionFile(String sharedRoot, String originalPackage,
                                       int versionCode) {
        if (!validPackage(originalPackage) || versionCode <= 0) return "";
        String filename = "main." + versionCode + "." + originalPackage + ".obb";
        String candidate = confine(sharedRoot, "obb", filename);
        return candidate != null && new File(candidate).isFile() ? candidate : "";
    }

    private static boolean validPackage(String name) {
        return name != null && PACKAGE.matcher(name).matches();
    }

    private static String confine(String sharedRoot, String category, String remainder) {
        if (remainder.indexOf('\0') >= 0) return null;
        final String[] segments = remainder.isEmpty() ? new String[0] : remainder.split("/", -1);
        for (String part : segments) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") ||
                part.indexOf('\\') >= 0 || part.indexOf(':') >= 0) return null;
        }
        try {
            File root = new File(sharedRoot, category).getCanonicalFile();
            File file = remainder.isEmpty() ? root : new File(root, remainder).getCanonicalFile();
            String base = root.getPath();
            String value = file.getPath();
            return value.equals(base) || value.startsWith(base + File.separator) ? value : null;
        } catch (IOException e) {
            return null;
        }
    }
}
