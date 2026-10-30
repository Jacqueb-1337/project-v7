package app.morphe.manager.domain.pv7

import android.content.res.AssetManager
import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

data class Pv7WrapperConfig(
    val packageName: String,
    val launcherLabel: String,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val launcherIcon: File? = null,
    val guestPackageName: String? = null,
    val requestedPermissions: Set<String> = emptySet()
)

data class Pv7GuestPayload(
    val root: File,
    val libMain: File,
    val libUnity: File,
    val libMono: File,
    val dataDir: File
)

class Pv7WrapperAssembler {
    companion object {
        const val LAUNCHER_ICON_APK_PATH = "res/drawable/pv7_launcher_icon.png"
        const val COMPAT_PROPERTIES_APK_PATH = "assets/pv7/compat.properties"
    }

    fun extractLegacyUnityGuest(inputApk: File, stagingRoot: File): Pv7GuestPayload {
        if (!inputApk.isFile) throw IOException("Input APK does not exist: " + inputApk)

        val target = Pv7AppTarget(
            packageId = "",
            versionName = null,
            abis = Pv7EngineDetector.detectAbis(inputApk),
            engine = Pv7EngineDetector.detect(inputApk)
        )
        if (!Pv7GuestBackend.canBuild(target)) {
            throw IOException("Unsupported PV7 guest format. " + Pv7GuestBackend.explanation(target))
        }

        if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) {
            throw IOException("Could not create PV7 staging directory: " + stagingRoot)
        }

        val guestDir = File(stagingRoot, "assets/guest").apply { mkdirs() }
        val dataDir = File(stagingRoot, "assets/bin/Data").apply { mkdirs() }
        val libMain = File(guestDir, "libmain.so")
        val libUnity = File(guestDir, "libunity.so")
        val libMono = File(guestDir, "libmono.so")

        ZipFile(inputApk).use { zip ->
            extractRequired(
                zip,
                listOf("lib/armeabi-v7a/libmain.so", "lib/armeabi/libmain.so"),
                libMain
            )
            extractRequired(
                zip,
                listOf("lib/armeabi-v7a/libunity.so", "lib/armeabi/libunity.so"),
                libUnity
            )
            extractRequired(
                zip,
                listOf("lib/armeabi-v7a/libmono.so", "lib/armeabi/libmono.so"),
                libMono
            )

            val dataEntries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith("assets/bin/Data/") }
                .toList()

            if (dataEntries.isEmpty()) {
                throw IOException("APK has no legacy Unity assets/bin/Data payload")
            }

            dataEntries.forEach { entry ->
                val relative = entry.name.removePrefix("assets/bin/Data/")
                if (relative.isBlank()) return@forEach
                val target = safeChild(dataDir, relative)
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use(input::copyTo)
                }
            }
        }

        val managed = File(dataDir, "Managed")
        if (!File(dataDir, "mainData").isFile) {
            throw IOException("Unity Data payload is missing mainData")
        }
        if (!File(managed, "mscorlib.dll").isFile) {
            throw IOException("Unity Managed payload is missing mscorlib.dll")
        }

        return Pv7GuestPayload(
            root = stagingRoot,
            libMain = libMain,
            libUnity = libUnity,
            libMono = libMono,
            dataDir = dataDir
        )
    }

    fun assembleFromTemplate(
        templateApk: File,
        guestPayload: Pv7GuestPayload,
        outputApk: File,
        config: Pv7WrapperConfig,
        assets: AssetManager,
        selectedModules: List<Pv7ModuleManifest>
    ) {
        if (!templateApk.isFile) {
            throw IOException("PV7 host template is missing: " + templateApk)
        }

        val module = ApkModule.loadApkFile(templateApk)
        try {
            module.setLoadDefaultFramework(false)
            module.setPackageName(config.packageName)

            val manifest = module.androidManifestBlock
            manifest.setApplicationLabel(config.launcherLabel)
            config.versionName?.takeIf { it.isNotBlank() }?.let(manifest::setVersionName)
            config.versionCode?.takeIf { it > 0 }?.let(manifest::setVersionCode)

            config.requestedPermissions
                .filter { it.isNotBlank() }
                .forEach { permission ->
                    if (manifest.getUsesPermission(permission) == null) {
                        manifest.addUsesPermission(permission)
                    }
                }
            applyDocumentsProviders(manifest, selectedModules, config.packageName)

            replaceFile(module, guestPayload.libMain, "assets/guest/libmain.so")
            replaceFile(module, guestPayload.libUnity, "assets/guest/libunity.so")
            replaceFile(module, guestPayload.libMono, "assets/guest/libmono.so")
            addDirectory(module, guestPayload.dataDir, "assets/bin/Data")
            overlayModulePayloads(module, assets, selectedModules, guestPayload.root)

            applyGenericGuestStoragePaths(module, config.guestPackageName?.takeIf { it.isNotBlank() } ?: config.packageName, guestPayload.root, config.versionCode)

            config.launcherIcon
                ?.takeIf { it.isFile }
                ?.let { replaceFile(module, it, LAUNCHER_ICON_APK_PATH) }

            module.refreshManifest()
            module.refreshTable()
            outputApk.parentFile?.mkdirs()
            module.writeApk(outputApk)
        } finally {
            runCatching { module.close() }
        }
    }

    private fun applyDocumentsProviders(
        manifest: AndroidManifestBlock,
        selectedModules: List<Pv7ModuleManifest>,
        packageName: String
    ) {
        // Always expose a SAF root for user-managed guest saves, cache, and OBB.
        // Its class is bundled in the generic host template as a secondary DEX.
        // Game-specific providers (e.g. CNRMods) continue to work alongside it.
        val genericProvider = "app.projectv7.wrapper.Pv7GameStorageDocumentsProvider" to ".gamefiles"
        val providers = listOf(genericProvider) + selectedModules.flatMap { module ->
            module.operations.mapNotNull { operation ->
                if (operation.type != "wrapper.addDocumentsProvider") return@mapNotNull null
                val value = operation.value as? JsonObject
                    ?: throw IOException("Module " + module.id + " has invalid documents-provider config")
                val className = value["className"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Module " + module.id + " documents provider is missing className")
                val authoritySuffix = value["authoritySuffix"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: ".documents"
                className to authoritySuffix
            }
        }

        val application = manifest.orCreateApplicationElement
        providers.distinct().forEach { (className, authoritySuffix) ->
            if (manifest.listApplicationElementsByTag("provider").any {
                    AndroidManifestBlock.getAndroidNameValue(it) == className
                }) {
                return@forEach
            }

            val provider = application.newElement("provider")
            provider.createAndroidAttribute("name", android.R.attr.name)
                .setValueAsString(className)
            provider.createAndroidAttribute("authorities", android.R.attr.authorities)
                .setValueAsString(packageName + authoritySuffix)
            provider.createAndroidAttribute("exported", android.R.attr.exported)
                .setValueAsBoolean(true)
            provider.createAndroidAttribute("grantUriPermissions", android.R.attr.grantUriPermissions)
                .setValueAsBoolean(true)
            provider.createAndroidAttribute("permission", android.R.attr.permission)
                .setValueAsString("android.permission.MANAGE_DOCUMENTS")

            val intentFilter = provider.newElement("intent-filter")
            val action = intentFilter.newElement("action")
            action.createAndroidAttribute("name", android.R.attr.name)
                .setValueAsString("android.content.action.DOCUMENTS_PROVIDER")
        }
    }
    private fun applyGenericGuestStoragePaths(
        apk: ApkModule,
        guestPackageName: String,
        scratchRoot: File,
        guestVersionCode: Int?
    ) {
        if (!Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")
                .matches(guestPackageName)) {
            throw IOException("Invalid original guest package for PV7 storage mapping")
        }
        val existing = apk.getInputSource(COMPAT_PROPERTIES_APK_PATH)
            ?.openStream()?.bufferedReader()?.use { it.readText() }.orEmpty()

        // The native PV7 compatibility runtime already accepts path.alias.N
        // and turns toFilesRelative into the wrapper's private filesDir.
        // Skip indices defined by other compatibility modules.
        var index = (Regex("(?m)^path\\.alias\\.(\\d+)\\.")
            .findAll(existing)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: -1) + 1
        val pathAliases = mutableListOf<Pair<String, String>>()
        val externalRoots = listOf(
            "/storage/emulated/0", "/sdcard", "/mnt/sdcard",
            "/storage/sdcard0", "/mnt/shell/emulated/0"
        )
        for (storageRoot in externalRoots) {
            val obb = "$storageRoot/Android/obb/$guestPackageName"
            pathAliases += obb to "GuestSharedStorage/obb"
            val androidData = "$storageRoot/Android/data/$guestPackageName"
            for (kind in listOf("files", "cache", "data")) {
                pathAliases += "$androidData/$kind" to "GuestSharedStorage/$kind"
            }
            pathAliases += androidData to "GuestSharedStorage/data"
        }
        for (appRoot in listOf(
            "/data/data/$guestPackageName",
            "/data/user/0/$guestPackageName",
            "/data/user_de/0/$guestPackageName"
        )) {
            for (kind in listOf("obb", "files", "cache", "data")) {
                pathAliases += "$appRoot/$kind" to "GuestSharedStorage/$kind"
            }
            pathAliases += appRoot to "GuestSharedStorage/data"
        }
        pathAliases += "/Android/obb/$guestPackageName" to "GuestSharedStorage/obb"
        pathAliases += "/Android/data/$guestPackageName/files" to "GuestSharedStorage/files"
        pathAliases += "/Android/data/$guestPackageName/cache" to "GuestSharedStorage/cache"
        pathAliases += "/Android/data/$guestPackageName/data" to "GuestSharedStorage/data"

        val text = buildString {
            append(existing.trimEnd())
            if (isNotEmpty()) append('\n')
            append("guest.package=").append(guestPackageName).append('\n')
            guestVersionCode?.takeIf { it > 0 }?.let {
                append("property.pv7.guestVersionCode=").append(it).append('\n')
            }
            // Do not let the former private-path handler take precedence
            // over the explicit shared files/cache/data aliases.
            append("path.rewritePrivate=false\n")
            for ((from, target) in pathAliases) {
                append("path.alias.").append(index).append(".from=").append(from).append('\n')
                append("path.alias.").append(index).append(".toFilesRelative=")
                    .append(target).append('\n')
                index++
            }
        }
        val generated = File(scratchRoot, "generated/compat.properties")
        generated.parentFile?.mkdirs()
        generated.writeText(text, Charsets.UTF_8)
        replaceFile(apk, generated, COMPAT_PROPERTIES_APK_PATH)
    }
    private fun overlayModulePayloads(
        apk: ApkModule,
        assets: AssetManager,
        selectedModules: List<Pv7ModuleManifest>,
        scratchRoot: File
    ) {
        val compatLayers = mutableListOf<String>()
        val ordered = selectedModules.sortedWith(
            compareBy<Pv7ModuleManifest> {
                if (it.kind == Pv7ModuleKind.GENERAL) 0 else 1
            }.thenBy { it.id }
        )

        ordered.forEach { module ->
            val sourceRoot = module.sourceAssetPath
            if (sourceRoot.isBlank()) return@forEach

            if (sourceRoot.startsWith("file:")) {
                val payloadRoot = File(sourceRoot.removePrefix("file:"), "payload")
                module.payloadFiles.forEach { relative ->
                    val source = safeChild(payloadRoot, relative)
                    if (!source.isFile) {
                        throw IOException("PV7 module payload missing: " + module.id + "/" + relative)
                    }
                    if (relative == COMPAT_PROPERTIES_APK_PATH) {
                        compatLayers += source.readText(Charsets.UTF_8)
                    } else {
                        replaceFile(apk, source, relative)
                    }
                }
                return@forEach
            }

            val assetModuleRoot = sourceRoot.removePrefix("asset:").trimEnd('/')
            val payloadRoot = assetModuleRoot + "/payload"
            if (module.payloadFiles.isNotEmpty()) {
                val moduleScratch = File(scratchRoot, "module-payloads/" + module.id)
                module.payloadFiles.forEach { relative ->
                    val target = safeChild(moduleScratch, relative)
                    target.parentFile?.mkdirs()
                    assets.open(payloadRoot + "/" + relative).use { input ->
                        FileOutputStream(target).use(input::copyTo)
                    }
                    if (relative == COMPAT_PROPERTIES_APK_PATH) {
                        compatLayers += target.readText(Charsets.UTF_8)
                    } else {
                        replaceFile(apk, target, relative)
                    }
                }
                return@forEach
            }

            val children = assets.list(payloadRoot).orEmpty()
            if (children.isEmpty()) return@forEach
            val moduleScratch = File(scratchRoot, "module-payloads/" + module.id)
            children.forEach { child ->
                overlayAssetNode(
                    apk = apk,
                    assets = assets,
                    assetPath = payloadRoot + "/" + child,
                    apkPath = child,
                    scratchRoot = moduleScratch
                )
            }
        }

        if (compatLayers.isNotEmpty()) {
            val templateText = apk.getInputSource(COMPAT_PROPERTIES_APK_PATH)
                ?.openStream()?.bufferedReader()?.use { it.readText() }.orEmpty()
            val merged = mergePv7CompatProperties(listOf(templateText) + compatLayers)
            val output = File(scratchRoot, "generated/merged-compat.properties")
            output.parentFile?.mkdirs()
            output.writeText(merged, Charsets.UTF_8)
            replaceFile(apk, output, COMPAT_PROPERTIES_APK_PATH)
        }
    }

    private fun overlayAssetNode(
        apk: ApkModule,
        assets: AssetManager,
        assetPath: String,
        apkPath: String,
        scratchRoot: File
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            children.forEach { child ->
                overlayAssetNode(
                    apk = apk,
                    assets = assets,
                    assetPath = assetPath + "/" + child,
                    apkPath = apkPath.trimEnd('/') + "/" + child,
                    scratchRoot = scratchRoot
                )
            }
            return
        }

        val target = safeChild(scratchRoot, apkPath)
        target.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            FileOutputStream(target).use(input::copyTo)
        }
        replaceFile(apk, target, apkPath)
    }

    private fun extractRequired(
        zip: ZipFile,
        candidates: List<String>,
        target: File
    ) {
        val entry = candidates.asSequence().mapNotNull(zip::getEntry).firstOrNull()
            ?: throw IOException(
                "Required legacy Unity library is missing. Tried: " + candidates.joinToString()
            )
        target.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            FileOutputStream(target).use(input::copyTo)
        }
    }

    private fun replaceFile(module: ApkModule, file: File, alias: String) {
        module.removeInputSource(alias)
        module.add(FileInputSource(file, alias))
    }

    private fun addDirectory(module: ApkModule, root: File, aliasRoot: String) {
        root.walkTopDown()
            .filter(File::isFile)
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                replaceFile(module, file, aliasRoot.trimEnd('/') + "/" + relative)
            }
    }

    private fun safeChild(root: File, relative: String): File {
        val target = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath)) {
            throw IOException("Unsafe APK entry path: " + relative)
        }
        return target
    }
}
