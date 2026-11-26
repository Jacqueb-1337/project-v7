package app.projectv7.wrapper;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class PerformanceOverlaySettingsTest {
    static int passed;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) {
        Map<String,Object> saved = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(), new Class[]{SharedPreferences.Editor.class},
            (proxy, method, a) -> {
                if (method.getName().equals("putInt") || method.getName().equals("putBoolean")) {
                    saved.put((String)a[0], a[1]); return proxy;
                }
                if (method.getName().equals("commit")) return true;
                throw new AssertionError(method.getName());
            });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class[]{SharedPreferences.class},
            (proxy, method, a) -> {
                if (method.getName().equals("getInt") || method.getName().equals("getBoolean"))
                    return saved.getOrDefault((String)a[0], a[1]);
                if (method.getName().equals("edit")) return editor;
                throw new AssertionError(method.getName());
            });
        String key = PerformanceOverlay.PREFIX;
        PerformanceOverlay.Settings s = PerformanceOverlay.Settings.read(prefs);
        check(s.background, "existing default background");
        check(s.textSize == 14, "existing default size");
        check(!s.enabled, "overlay stays opt-in");
        for (int i=4;i<=32;i++) {
            s.textSize=i; s.background=false; s.enabled=true;
            s.write(editor);
            PerformanceOverlay.Settings roundtrip=PerformanceOverlay.Settings.read(prefs);
            check(roundtrip.textSize==i, "size roundtrip "+i);
            check(!roundtrip.background, "transparent roundtrip");
        }
        saved.put(key+"size", 0);
        check(PerformanceOverlay.Settings.read(prefs).textSize==4,"lower clamp");
        saved.put(key+"size", 100);
        check(PerformanceOverlay.Settings.read(prefs).textSize==32,"upper clamp");
        s.background=true; s.write(editor);
        check(PerformanceOverlay.Settings.read(prefs).background,"re-enable background");
        saved.clear();
        check(PerformanceOverlay.Settings.read(prefs).textSize==14,"reset size");
        check(PerformanceOverlay.Settings.read(prefs).background,"reset background");
        System.out.println("PASS "+passed+" overlay preference checks");
    }
}
