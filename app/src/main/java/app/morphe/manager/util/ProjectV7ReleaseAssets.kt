package app.morphe.manager.util

import java.util.Locale

/**
 * Preview/debug installs use a different Android application ID and signing identity.
 * Never offer an APK from the wrong channel as an in-app manager update.
 */
internal fun isProjectV7ManagerApkFile(name: String, debugChannel: Boolean): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    if (!normalized.startsWith("project-v7-patcher-") || !normalized.endsWith(".apk")) {
        return false
    }
    if (normalized.endsWith("-unsigned.apk") || normalized.endsWith("-unaligned.apk")) {
        return false
    }
    return normalized.endsWith("-debug.apk") == debugChannel
}
