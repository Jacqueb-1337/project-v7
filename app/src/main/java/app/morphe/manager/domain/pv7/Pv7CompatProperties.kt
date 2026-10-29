package app.morphe.manager.domain.pv7

/** Later module properties override earlier ones, preserving independent general policies. */
internal fun mergePv7CompatProperties(texts: List<String>): String {
    val entries = linkedMapOf<String, String>()
    for (text in texts) {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val index = trimmed.indexOf('=')
            if (index <= 0) continue
            val key = trimmed.substring(0, index).trim()
            if (key.isBlank()) continue
            entries[key] = trimmed.substring(index + 1).trim()
        }
    }
    return entries.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) ->
        "$key=$value"
    }
}
