package app.morphe.manager.domain.pv7
import android.content.res.AssetManager
import sun.misc.Unsafe
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.Properties
import org.junit.Test

class Pv7CocosAssemblyTest {
@Test fun assemblesUnofficialCocosWithoutUnityAndRejectsWrongBackend() {
    val project = sequenceOf(File("."),File("..")).first {
        File(it,"app/src/main/assets/pv7/templates/pv7-host-template.apk").isFile
    }
    val args = arrayOf(
        File(project,"app/src/main/assets/pv7/templates/pv7-host-template.apk").absolutePath,
        File(project,"catalog/modules/general/cocos2dx-compat").absolutePath
    )
    val root=Files.createTempDirectory("pv7-synthetic-assembly").toFile()
    val staging=File(root,"staging")
    try {
        val image=ByteArray(128)
        image[0]=127; image[1]=69;image[2]=76;image[3]=70;image[4]=1;image[5]=1;image[18]=40
        val apk=File(root,"synthetic.apk")
        ZipOutputStream(apk.outputStream()).use { z ->
            for((name,bytes) in mapOf("lib/armeabi/libgame.so" to image,
                "org/cocos2dx/lib/Cocos2dxRenderer.class" to byteArrayOf(1),
                "assets/test.txt" to "fixture".toByteArray(),
                "assets/pv7/host.properties" to "guest.bestPark=true".toByteArray())) {
                z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()
            }
        }
        val staged=Pv7CocosGuestPackager.stage(apk,staging)
        val module=Pv7ModuleManifest("cocos2dx-compat","Cocos","0.1",Pv7ModuleKind.GENERAL,description="synthetic test",
          targets=Pv7TargetSelector(engines=listOf("cocos2dx")),
          payloadFiles=listOf("lib/arm64-v8a/libpv7cocosprobe.so.pv7z","lib/x86_64/libpv7cocosprobe.so.pv7z"),
          sourceAssetPath="file:"+args[1])
        val unsafe=Unsafe::class.java.getDeclaredField("theUnsafe").apply {isAccessible=true}.get(null) as Unsafe
        val assets=unsafe.allocateInstance(AssetManager::class.java) as AssetManager
        val output=File(root,"wrapper.apk")
        val config=Pv7WrapperConfig("com.example.synthetic.pv7","Synthetic fixture", "1.0",7,guestPackageName="com.example.synthetic")
        val assembler=Pv7WrapperAssembler()
        assembler.assembleFromTemplate(File(args[0]),staged,output,config,assets,listOf(module))
        ZipFile(output).use { z ->
            fun bytes(name:String)=z.getInputStream(z.getEntry(name)).use {it.readBytes()}
            check(bytes("assets/guest/libgame.so").contentEquals(image))
            check(bytes("assets/test.txt").decodeToString()=="fixture")
            check(z.getEntry("assets/guest/libunity.so")==null)
            check(z.getEntry("assets/guest/libmono.so")==null)
            val props=Properties().apply {load(bytes("assets/pv7/host.properties").inputStream())}
            check(props.getProperty("guest.engine")=="cocos2dx")
            check(props.getProperty("guest.package")=="com.example.synthetic")
            check(props.getProperty("guest.versionCode")=="7")
            check(props.getProperty("guest.bestPark")=="false")
            for(abi in listOf("arm64-v8a","x86_64")) check(bytes("lib/$abi/libpv7cocosprobe.so").take(4)==listOf(127.toByte(),69.toByte(),76.toByte(),70.toByte()))
        }
        val wrong=Pv7ModuleManifest("unity-4.6-compat","Unity","1",Pv7ModuleKind.GENERAL,description="test",targets=Pv7TargetSelector(engines=listOf("unity")))
        check(runCatching {assembler.assembleFromTemplate(File(args[0]),staged,File(root,"invalid.apk"),config,assets,listOf(module,wrong))}.isFailure)
        check(runCatching {assembler.assembleFromTemplate(File(args[0]),staged,File(root,"missing.apk"),config,assets,emptyList())}.isFailure)
        println("Synthetic Cocos assembly and backend isolation checks passed")
    } finally {root.deleteRecursively()}
}

}
