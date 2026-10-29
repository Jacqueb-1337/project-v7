package app.morphe.manager.util

/**
 * Project V7 deliberately does not subscribe to Morphe's Firebase project.
 *
 * PV7's background release checks run through [app.morphe.manager.worker.UpdateCheckWorker].
 * Once a PV7-owned Firebase project exists, push notifications can be added separately.
 *
 * Kept as a compatibility shim for existing settings call sites.
 */
fun syncFcmTopics(
    notificationsEnabled: Boolean,
    useManagerPrereleases: Boolean,
    usePatchesPrereleases: Boolean = false,
) {
    // Intentionally no-op: never subscribe PV7 users to Morphe's FCM topics.
}
