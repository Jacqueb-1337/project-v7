package app.morphe.manager.domain.pv7

/**
 * PV7 can inspect any APK, but the bundled host can currently execute only
 * legacy ARMv7 Unity 4.6 Mono guests. Generic core modules do not add a
 * new native engine or supply a Cocos2d-x JNI/GL runtime.
 */
object Pv7GuestBackend {
    fun canBuild(target: Pv7AppTarget): Boolean =
        target.engine?.id == "unity" &&
            (target.engine.version == null || target.engine.version.startsWith("4.6.")) &&
            target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun explanation(target: Pv7AppTarget): String = when (target.engine?.id) {
        "cocos2dx" ->
            "Cocos2d-x detected (32-bit native libgame.so). PV7 can inspect this APK, " +
                "but its bundled runtime currently supports legacy Unity 4.6 Mono only. " +
                "Cocos2d-x requires a separate native/JNI/graphics compatibility backend."
        "unity" ->
            "This Unity build does not match the legacy ARMv7 Unity 4.6 Mono " +
                "runtime the current PV7 host supports. No compatible backend is available."
        else ->
            "This APK's native game engine is not supported by the current PV7 " +
                "wrapper. You can inspect modules, but building a working patched APK " +
                "requires a compatible runtime backend."
    }
}
