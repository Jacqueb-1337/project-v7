package app.morphe.manager.domain.pv7

import android.content.res.AssetManager

import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

data class Pv7WrapperConfig(
    val packageName: String,
    val launcherLabel: String? = null
)

data class Pv7GuestPayload(
    val root: File,
    val libMain: File,
    val libUnity: File,
    val libMono: File,
    val dataDir: File
)

class Pv7WrapperAssembler {
    fun extractLegacyUnityGuest(inputApk: File, stagingRoot: File): Pv7GuestPayload {
        if (!inputApk.isFile) throw IOException("Input APK does not exist: " + inputApk)

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
                listOf(
                    "lib/armeabi-v7a/libmain.so",
                    "lib/armeabi/libmain.so"
                ),
                libMain
            )
            extractRequired(
                zip,
                listOf(
                    "lib/armeabi-v7a/libunity.so",
                    "lib/armeabi/libunity.so"
                ),
                libUnity
            )
            extractRequired(
                zip,
                listOf(
                    "lib/armeabi-v7a/libmono.so",
                    "lib/armeabi/libmono.so"
                ),
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

            replaceFile(module, guestPayload.libMain, "assets/guest/libmain.so")
            replaceFile(module, guestPayload.libUnity, "assets/guest/libunity.so")
            replaceFile(module, guestPayload.libMono, "assets/guest/libmono.so")
            addDirectory(module, guestPayload.dataDir, "assets/bin/Data")
            overlayModulePayloads(module, assets, selectedModules, guestPayload.root)

            module.refreshManifest()
            module.refreshTable()
            outputApk.parentFile?.mkdirs()
            module.writeApk(outputApk)
        } finally {
            runCatching { module.close() }
        }
    }


    private fun overlayModulePayloads(
        apk: ApkModule,
        assets: AssetManager,
        selectedModules: List<Pv7ModuleManifest>,
        scratchRoot: File
    ) {
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
                    replaceFile(apk, source, relative)
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
                    replaceFile(apk, target, relative)
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