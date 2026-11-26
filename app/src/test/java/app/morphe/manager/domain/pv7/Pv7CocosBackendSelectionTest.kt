package app.morphe.manager.domain.pv7
import org.junit.Test

class Pv7CocosBackendSelectionTest {
@Test fun experimentalCocosIsSelectableAndUnityIsRejected() {
    fun module(id:String, engine:String?=null, deps:List<String> = emptyList(), pkgs:List<String> = emptyList()) =
        Pv7ModuleManifest(id,id,"1",Pv7ModuleKind.GENERAL,description=id,
            targets=Pv7TargetSelector(engines=listOfNotNull(engine),packageIds=pkgs,abis=listOf("armeabi","armeabi-v7a")),
            suggestedWhenMatched=true,dependencies=deps)
    val mods=listOf(module("core-compat"),module("unity-4.6-compat","unity",listOf("core-compat")),module("cocos2dx-compat","cocos2dx",listOf("core-compat")),module("best-park-compat","cocos2dx",listOf("cocos2dx-compat"),listOf("com.turner.bestparkintheuniverse")))
    val unknown=Pv7AppTarget("com.example.unknown","7",setOf("armeabi"),Pv7EngineFingerprint("cocos2dx",null,"libgame.so"))
    val park=unknown.copy(packageId="com.turner.bestparkintheuniverse",versionName="1.2.0",engine=Pv7EngineFingerprint("cocos2dx","cocos2d-2.1rc0-x-2.1.2","libgame.so"))
    val unity=unknown.copy(engine=Pv7EngineFingerprint("unity","4.6.1f1","libunity.so"))
    val a=Pv7CatalogResolver.resolve(unknown,mods,emptyList())
    check(Pv7GuestBackend.canBuild(unknown))
    check(Pv7GuestBackend.canProceed(unknown,a.selectedModuleIds))
    check(a.selectedModuleIds==setOf("core-compat","cocos2dx-compat"))
    check(a.visibleModules.none { it.manifest.id=="unity-4.6-compat" })
    check(a.hiddenModules.any { it.manifest.id=="unity-4.6-compat" })
    check(!Pv7GuestBackend.canProceed(unknown,setOf("core-compat")))
    val injected=Pv7CatalogResolver.resolve(unknown,mods,emptyList(),setOf("unity-4.6-compat","cocos2dx-compat"))
    check(injected.issues.isNotEmpty() && "unity-4.6-compat" !in injected.selectedModuleIds)
    val b=Pv7CatalogResolver.resolve(park,mods,emptyList())
    check(Pv7GuestBackend.isBestPark(park))
    check(b.selectedModuleIds==setOf("core-compat","cocos2dx-compat","best-park-compat"))
    val c=Pv7CatalogResolver.resolve(unity,mods,emptyList())
    check(c.selectedModuleIds==setOf("core-compat","unity-4.6-compat"))
    check(c.visibleModules.none { it.manifest.id=="cocos2dx-compat" })
    check(!Pv7GuestBackend.canBuild(unknown.copy(abis=setOf("arm64-v8a"))))
    check(!Pv7GuestBackend.canBuild(unknown.copy(engine=null)))
    println("13 backend selection and engine filtering checks passed")
}

}
