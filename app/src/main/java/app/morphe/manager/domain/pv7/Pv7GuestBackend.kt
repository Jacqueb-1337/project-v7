package app.morphe.manager.domain.pv7

object Pv7GuestBackend {
    const val BEST_PARK_PACKAGE = "com.turner.bestparkintheuniverse"
    const val BEST_PARK_VERSION = "1.2.0"
    const val BEST_PARK_MODULE = "best-park-compat"
    const val COCOS_MODULE = "cocos2dx-compat"
    const val BEST_PARK_ENGINE = "cocos2d-2.1rc0-x-2.1.2"

    fun isUnity(target: Pv7AppTarget): Boolean =
        target.engine?.id == "unity" &&
            (target.engine.version == null || target.engine.version.startsWith("4.6.")) &&
            target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun isBestPark(target: Pv7AppTarget): Boolean =
        target.packageId == BEST_PARK_PACKAGE && target.versionName == BEST_PARK_VERSION &&
            target.engine?.id == "cocos2dx" && target.engine.version == BEST_PARK_ENGINE &&
            target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun isCocos(target: Pv7AppTarget): Boolean =
        target.engine?.id == "cocos2dx" && target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun canBuild(target: Pv7AppTarget): Boolean = isUnity(target) || isCocos(target)

    fun canProceed(target: Pv7AppTarget, selected: Set<String>): Boolean =
        canBuild(target) && (!isCocos(target) || COCOS_MODULE in selected)

    fun selectionExplanation(target: Pv7AppTarget): String =
        if (isCocos(target)) "Select Cocos2d-x Compatibility to continue. Unofficial games are experimental."
        else explanation(target)

    fun explanation(target: Pv7AppTarget): String = when (target.engine?.id) {
        "cocos2dx" -> "Cocos2d-x requires a detected ARM32 guest library (armeabi or armeabi-v7a)."
        "unity" -> "This Unity build does not match the legacy ARMv7 Unity 4.6 Mono runtime."
        else -> "This APK requires a compatible native runtime backend."
    }
}
