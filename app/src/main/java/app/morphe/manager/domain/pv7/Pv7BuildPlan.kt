package app.morphe.manager.domain.pv7

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class Pv7RequiredOption(
    val moduleId: String,
    val operationType: String,
    val optionKey: String
) {
    val storageKey: String get() = pv7OptionKey(moduleId, optionKey)
}

fun pv7OptionKey(moduleId: String, optionKey: String): String =
    moduleId + ":" + optionKey

data class Pv7BuildPlan(
    val selectedModuleIds: Set<String>,
    val enableCoreCompatibility: Boolean,
    val compatModules: Set<String>,
    val requiredOptions: List<Pv7RequiredOption>,
    val selectedModules: List<Pv7ModuleManifest>
) {
    val requiresWrapper: Boolean
        get() = enableCoreCompatibility || compatModules.isNotEmpty() || requiredOptions.isNotEmpty()

    companion object {
        val Empty = Pv7BuildPlan(
            selectedModuleIds = emptySet(),
            enableCoreCompatibility = false,
            compatModules = emptySet(),
            requiredOptions = emptyList(),
            selectedModules = emptyList()
        )
    }
}

class Pv7BuildPlanner(private val context: Context) {
    fun create(selectedModuleIds: Set<String>): Pv7BuildPlan {
        if (selectedModuleIds.isEmpty()) return Pv7BuildPlan.Empty

        val catalog = Pv7CatalogLoader(context).load(refreshRemote = false)
        if (catalog.issues.isNotEmpty()) {
            throw IllegalStateException(
                "PV7 catalog is invalid: " + catalog.issues.joinToString("; ")
            )
        }

        val byId = catalog.modules.associateBy { it.id }
        val missingModules = selectedModuleIds.filterNot(byId::containsKey)
        if (missingModules.isNotEmpty()) {
            throw IllegalStateException(
                "Missing selected PV7 module(s): " + missingModules.sorted().joinToString()
            )
        }

        val selectedModules = selectedModuleIds.mapNotNull(byId::get)
        val missingDependencies = selectedModules.flatMap { module ->
            module.dependencies
                .filterNot(selectedModuleIds::contains)
                .map { dependency -> module.id + " -> " + dependency }
        }
        if (missingDependencies.isNotEmpty()) {
            throw IllegalStateException(
                "PV7 dependency closure is incomplete: " + missingDependencies.joinToString()
            )
        }

        var enableCoreCompatibility = false
        val compatModules = linkedSetOf<String>()
        val requiredOptions = mutableListOf<Pv7RequiredOption>()
        val unknownOperations = mutableListOf<String>()

        selectedModules.forEach { module ->
            module.operations.forEach { operation ->
                when (operation.type) {
                    "runtime.enableCoreCompatibility" -> enableCoreCompatibility = true
                    "compat.module" -> {
                        val compatId = (operation.value as? JsonPrimitive)?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException(
                                "Module " + module.id + " has compat.module without a module id"
                            )
                        compatModules += compatId
                    }
                    "wrapper.addDocumentsProvider" -> Unit
                    "wrapper.setPackageId",
                    "wrapper.setLauncherLabel",
                    "wrapper.setLauncherIcon",
                    "guest.setPackageId" -> {
                        val option = operation.option?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException(
                                "Module " + module.id + " operation " + operation.type +
                                    " does not declare an option key"
                            )
                        requiredOptions += Pv7RequiredOption(
                            moduleId = module.id,
                            operationType = operation.type,
                            optionKey = option
                        )
                    }
                    else -> unknownOperations += module.id + ":" + operation.type
                }
            }
        }

        if (unknownOperations.isNotEmpty()) {
            throw IllegalStateException(
                "Unsupported PV7 operation(s): " + unknownOperations.sorted().joinToString()
            )
        }

        return Pv7BuildPlan(
            selectedModuleIds = selectedModuleIds,
            enableCoreCompatibility = enableCoreCompatibility,
            compatModules = compatModules,
            requiredOptions = requiredOptions,
            selectedModules = selectedModules
        )
    }
}
