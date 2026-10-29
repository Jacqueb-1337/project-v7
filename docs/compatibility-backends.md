# Project V7 compatibility backends

**Catalog modules and native compatibility backends are different.** A catalog
module can select settings, package metadata, a guest file overlay, or validated
patches. It cannot make an incompatible engine launch through a runtime that
does not implement that engine's native ABI/JNI contract.

## Available runtime

PV7's currently bundled host supports **experimental ARMv7 legacy Unity 4.6
Mono** applications. The only tested baseline remains Cops N Robbers 3.0.2
(Unity 4.6.1f1). It expects libmain.so, libunity.so, libmono.so,
assets/bin/Data/mainData, and assets/bin/Data/Managed/mscorlib.dll.
Other Unity versions and APK packaging layouts may need different backends.

## Best Park / Regular Show (v1.2.1)

The supplied Best Park APK (com.turner.bestparkintheuniverse, v1.2.1)
contains lib/armeabi/libgame.so and org/cocos2dx/lib/ resources.
It does **not** use the Unity Mono layout. Its engine is identified as
**Cocos2d-x**. The 32-bit native library alone does not mean it can run under
the Unity wrapper. It needs its own JNI entry-point/lifecycle bridge,
OpenGL ES context and input/audio/file-system handling, and an appropriately
configured ARM32-to-ARM64 translation pathway.

PV7 will detect this format, report the missing backend, and disable
Continue for unsupported formats instead of attempting to extract
Unity-only libraries and failing at libmain.so.

## Morphe Patches source

Project V7 uses its **own PV7 module catalog**. The legacy preinstalled
Morphe Patches bundle requires Morphe's unrelated patch engine 2.0.0 and is
not a PV7 compatibility module. PV7 no longer loads or recreates that
preinstalled source. Existing database records are preserved but ignored;
users may still add separate patch sources voluntarily.

See the compatibility-module and contribution documentation for module
authors. Engine support should only be claimed after a real runtime test.
