package app.morphe.manager.domain.pv7

/** Deterministic semantic version ordering for independently released PV7 modules. */
object Pv7ModuleVersions {
    private val pattern = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([A-Za-z0-9.-]+))?$""")

    fun normalized(value: String): String = value.trim().removePrefix("v")

    fun compare(left: String, right: String): Int {
        val a = pattern.matchEntire(left.trim()) ?: return left.compareTo(right)
        val b = pattern.matchEntire(right.trim()) ?: return left.compareTo(right)
        for (index in 1..3) {
            val n = (a.groupValues[index].toLongOrNull() ?: 0L)
                .compareTo(b.groupValues[index].toLongOrNull() ?: 0L)
            if (n != 0) return n
        }
        val first = a.groupValues[4]
        val second = b.groupValues[4]
        if (first.isEmpty() && second.isNotEmpty()) return 1
        if (second.isEmpty() && first.isNotEmpty()) return -1
        return first.compareTo(second)
    }
}
