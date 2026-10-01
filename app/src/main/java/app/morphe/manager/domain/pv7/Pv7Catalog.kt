package app.morphe.manager.domain.pv7

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class Pv7ModuleKind {
    @SerialName("general")
    GENERAL,

    @SerialName("game")
    GAME
}

@Serializable
data class Pv7TargetSelector(
    val packageIds: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    val abis: List<String> = emptyList(),
    val engines: List<String> = emptyList(),
    val engineVersions: List<String> = emptyList()
)

@Serializable
data class Pv7ModuleConflict(
    val moduleId: String,
    val reason: String = ""
)

@Serializable
data class Pv7PatchOperation(
    val type: String,
    val option: String? = null,
    val value: JsonElement? = null,
    val path: String? = null,
    val description: String? = null
)

@Serializable
data class Pv7ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val kind: Pv7ModuleKind,
    val category: String = "Other",
    val description: String,
    val targets: Pv7TargetSelector = Pv7TargetSelector(),
    val suggestedWhenMatched: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val conflicts: List<Pv7ModuleConflict> = emptyList(),
    val operations: List<Pv7PatchOperation> = emptyList()
)

@Serializable
data class Pv7BlacklistRule(
    val moduleId: String,
    val reason: String = "",
    val whenAnySelected: List<String> = emptyList()
)

@Serializable
data class Pv7CompatibilityProfile(
    val id: String,
    val packageId: String,
    val versions: List<String> = emptyList(),
    val abis: List<String> = emptyList(),
    val engines: List<String> = emptyList(),
    val engineVersions: List<String> = emptyList(),
    val profileVersion: Int = 1,
    val maintainer: String? = null,
    val tested: Boolean = true,
    val recommendedModules: List<String> = emptyList(),
    val blacklist: List<Pv7BlacklistRule> = emptyList(),
    val notes: String? = null
)

data class Pv7AppTarget(
    val packageId: String,
    val versionName: String?,
    val abis: Set<String> = emptySet(),
    val engine: Pv7EngineFingerprint? = null
)

enum class Pv7ModuleBadge {
    RECOMMENDED,
    DEPENDENCY,
    COMPATIBLE,
    ENGINE_SUGGESTED,
    UNTESTED
}

data class Pv7VisibleModule(
    val manifest: Pv7ModuleManifest,
    val badge: Pv7ModuleBadge,
    val selected: Boolean,
    val recommendationReason: String? = null
)

data class Pv7HiddenModule(
    val manifest: Pv7ModuleManifest,
    val reason: String
)

data class Pv7ResolutionPlan(
    val profile: Pv7CompatibilityProfile?,
    val selectedModuleIds: Set<String>,
    val visibleModules: List<Pv7VisibleModule>,
    val hiddenModules: List<Pv7HiddenModule>,
    val issues: List<String>
)

object Pv7CatalogResolver {
    fun resolve(
        target: Pv7AppTarget,
        modules: List<Pv7ModuleManifest>,
        profiles: List<Pv7CompatibilityProfile>,
        userSelectedModuleIds: Set<String>? = null
    ): Pv7ResolutionPlan {
        val byId = modules.associateBy { it.id }
        val profile = profiles
            .filter { it.tested && it.matches(target) }
            .maxByOrNull { it.matchSpecificity(target) }

        val testedRecommended = profile?.recommendedModules.orEmpty().toSet()
        val engineSuggested = if (profile == null) {
            modules.filter { it.suggestedWhenMatched && it.isDesignedFor(target) }.mapTo(linkedSetOf()) { it.id }
        } else {
            emptySet()
        }
        val recommended = if (profile != null) testedRecommended else engineSuggested
        val explicitSelection = userSelectedModuleIds ?: recommended
        val selected = linkedSetOf<String>()
        val dependencyOnly = linkedSetOf<String>()
        val issues = mutableListOf<String>()

        val unconditionalBlacklist = profile?.blacklist.orEmpty()
            .filter { it.whenAnySelected.isEmpty() }
            .associateBy { it.moduleId }

        fun addWithDependencies(moduleId: String, root: Boolean) {
            if (moduleId in selected) return

            val blocked = unconditionalBlacklist[moduleId]
            if (blocked != null) {
                issues += blocked.reason.ifBlank {
                    "Module " + moduleId + " is blacklisted for this app profile."
                }
                return
            }

            val module = byId[moduleId]
            if (module == null) {
                issues += "Missing compatibility module: " + moduleId
                return
            }

            selected += moduleId
            if (!root) dependencyOnly += moduleId

            module.dependencies.forEach { dependencyId ->
                addWithDependencies(dependencyId, root = false)
            }
        }

        explicitSelection.forEach { addWithDependencies(it, root = true) }

        val conditionalBlacklist = profile?.blacklist.orEmpty()
            .filter { rule ->
                rule.whenAnySelected.isNotEmpty() &&
                    rule.whenAnySelected.any(selected::contains)
            }
            .associateBy { it.moduleId }

        val activeBlacklist = unconditionalBlacklist + conditionalBlacklist

        activeBlacklist.forEach { (moduleId, rule) ->
            if (selected.remove(moduleId)) {
                dependencyOnly.remove(moduleId)
                issues += rule.reason.ifBlank {
                    "Module " + moduleId + " became incompatible with the current selection."
                }
            }
        }

        val selectedManifests = selected.mapNotNull(byId::get)

        for (module in selectedManifests) {
            for (conflict in module.conflicts) {
                if (conflict.moduleId in selected) {
                    issues += conflict.reason.ifBlank {
                        "Modules " + module.id + " and " + conflict.moduleId + " cannot be used together."
                    }
                }
            }
        }

        fun hardConflictReason(candidate: Pv7ModuleManifest): String? {
            if (candidate.id in selected) return null

            candidate.conflicts.firstOrNull { it.moduleId in selected }?.let { conflict ->
                return conflict.reason.ifBlank {
                    candidate.name + " conflicts with a selected module."
                }
            }

            selectedManifests.firstOrNull { selectedModule ->
                selectedModule.conflicts.any { it.moduleId == candidate.id }
            }?.let { selectedModule ->
                val conflict = selectedModule.conflicts.first { it.moduleId == candidate.id }
                return conflict.reason.ifBlank {
                    candidate.name + " conflicts with " + selectedModule.name + "."
                }
            }

            return null
        }

        val hidden = mutableListOf<Pv7HiddenModule>()
        val visible = mutableListOf<Pv7VisibleModule>()

        modules.sortedWith(compareBy<Pv7ModuleManifest>({ it.category }, { it.name })).forEach { module ->
            val blacklistRule = activeBlacklist[module.id]
            if (blacklistRule != null) {
                hidden += Pv7HiddenModule(
                    module,
                    blacklistRule.reason.ifBlank { "Known incompatible with this app/profile." }
                )
                return@forEach
            }

            val conflictReason = hardConflictReason(module)
            if (conflictReason != null) {
                hidden += Pv7HiddenModule(module, conflictReason)
                return@forEach
            }

            val badge = when {
                module.id in testedRecommended -> Pv7ModuleBadge.RECOMMENDED
                module.id in engineSuggested -> Pv7ModuleBadge.ENGINE_SUGGESTED
                module.id in dependencyOnly -> Pv7ModuleBadge.DEPENDENCY
                module.isDesignedFor(target) -> Pv7ModuleBadge.COMPATIBLE
                else -> Pv7ModuleBadge.UNTESTED
            }

            visible += Pv7VisibleModule(
                manifest = module,
                badge = badge,
                selected = module.id in selected,
                recommendationReason = if (module.id in recommended) {
                    "Tested and recommended by the Project V7 compatibility profile for this app/version."
                } else {
                    null
                }
            )
        }

        return Pv7ResolutionPlan(
            profile = profile,
            selectedModuleIds = selected,
            visibleModules = visible,
            hiddenModules = hidden,
            issues = issues.distinct()
        )
    }

    private fun Pv7CompatibilityProfile.matches(target: Pv7AppTarget): Boolean {
        if (packageId != target.packageId) return false
        if (versions.isNotEmpty() && target.versionName !in versions) return false
        if (abis.isNotEmpty() && target.abis.none(abis::contains)) return false
        if (engines.isNotEmpty() && target.engine?.id !in engines) return false
        if (engineVersions.isNotEmpty() && !matchesAnyVersion(target.engine?.version, engineVersions)) return false
        return true
    }

    private fun Pv7CompatibilityProfile.matchSpecificity(target: Pv7AppTarget): Int {
        var score = 1
        if (versions.isNotEmpty() && target.versionName in versions) score += 8
        if (abis.isNotEmpty() && target.abis.any(abis::contains)) score += 4
        if (engines.isNotEmpty() && target.engine?.id in engines) score += 2
        if (engineVersions.isNotEmpty() && matchesAnyVersion(target.engine?.version, engineVersions)) score += 2
        return score
    }

    private fun Pv7ModuleManifest.isDesignedFor(target: Pv7AppTarget): Boolean {
        if (targets.packageIds.isNotEmpty() && target.packageId !in targets.packageIds) return false
        if (targets.versions.isNotEmpty() && target.versionName !in targets.versions) return false
        if (targets.abis.isNotEmpty() && target.abis.none(targets.abis::contains)) return false
        if (targets.engines.isNotEmpty() && target.engine?.id !in targets.engines) return false
        if (targets.engineVersions.isNotEmpty() && !matchesAnyVersion(target.engine?.version, targets.engineVersions)) return false
        return true
    }
}

private fun matchesAnyVersion(value: String?, patterns: List<String>): Boolean {
    if (patterns.isEmpty()) return true
    if (value == null) return false
    return patterns.any { pattern ->
        val body = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        Regex("^" + body + "$", RegexOption.IGNORE_CASE).matches(value)
    }
}
