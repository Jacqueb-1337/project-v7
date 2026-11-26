package app.morphe.manager.domain.pv7

object Pv7GuestBackend {
    const val BEST_PARK_PACKAGE = "com.turner.bestparkintheuniverse"
    val BEST_PARK_VERSIONS = setOf("1.2.0", "1.2.1")
    const val BEST_PARK_MODULE = "best-park-compat"
    const val UNITY56_MODULE = "unity-5.6-compat"
    const val COCOS_MODULE = "cocos2dx-compat"
    const val BEST_PARK_ENGINE = "cocos2d-2.1rc0-x-2.1.2"

    fun isUnity(target: Pv7AppTarget): Boolean =
        target.engine?.id == "unity" &&
            (target.engine.version == null || target.engine.version.startsWith("4.6.") || target.engine.version.startsWith("5.6.")) &&
            target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun isExperimentalUnity56(target: Pv7AppTarget): Boolean =
        target.engine?.id == "unity" && target.engine.version?.startsWith("5.6.") == true

    fun isBestPark(target: Pv7AppTarget): Boolean =
        target.packageId == BEST_PARK_PACKAGE && target.versionName in BEST_PARK_VERSIONS &&
            target.engine?.id == "cocos2dx" &&
            target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun isCocos(target: Pv7AppTarget): Boolean =
        target.engine?.id == "cocos2dx" && target.abis.any { it == "armeabi" || it == "armeabi-v7a" }

    fun canBuild(target: Pv7AppTarget): Boolean = isUnity(target) || isCocos(target)

    fun canProceed(target: Pv7AppTarget, selected: Set<String>): Boolean =
        canBuild(target) && (!isCocos(target) || COCOS_MODULE in selected) &&
            (!isBestPark(target) || BEST_PARK_MODULE in selected) &&
            (target.engine?.version != "5.6.1f1" || UNITY56_MODULE in selected)

    fun selectionExplanation(target: Pv7AppTarget): String =
        if (isBestPark(target)) "Select Best Park Cocos compatibility and its Cocos2d-x dependency to continue."
        else if (isCocos(target)) "Select Cocos2d-x Compatibility to continue. Unofficial games are experimental."
        else if (target.engine?.version == "5.6.1f1") "Select Unity 5.6 Compatibility to continue. Other games are unverified."
        else explanation(target)

    fun explanation(target: Pv7AppTarget): String = when (target.engine?.id) {
        "cocos2dx" -> "Cocos2d-x requires a detected ARM32 guest library (armeabi or armeabi-v7a)."
        "unity" -> if (isExperimentalUnity56(target)) {
            "Experimental Unity 5.6 guest: the dedicated runtime has been exercised only with Guns and Spurs 1.3.3."
        } else "This Unity build does not match the legacy ARMv7 Unity 4.6 Mono runtime."
        else -> "This APK requires a compatible native runtime backend."
    }
}
