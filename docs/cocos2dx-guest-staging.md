# Cocos2d-x guest staging (milestone 1)

**Status:** Engine and ABI detection is merged; native extraction/staging
is implemented in `Pv7CocosGuestPackager`. This is **not yet a runnable
Cocos backend**; PV7 must continue to disable the final Build button for
Cocos APKs until the native/JNI execution bridge is ready.

## Input: Best Park 1.2.1

The locally inspected `08093-Best-Park-v1-2-1.apk` contains:

- `lib/armeabi/libgame.so`, an ELF32 little-endian ARM native game library.
- Cocos2d-x classes under `org/cocos2dx/lib`.
- Exported `JNI_OnLoad`, `Cocos2dxRenderer_nativeInit`,
  `nativeRender`, `nativeOnPause`, `nativeOnResume`,
  and native touch/text entry points.
- Native dependencies `liblog`, `libz`, `libGLESv2`,
  `libstdc++`, `libm`, `libc`, and `libdl`.

## Staged guest payload

Calling `Pv7CocosGuestPackager.stage(originalApk, stagingRoot)` produces:

```text
stagingRoot/
└── assets/
    └── guest/
        ├── libgame.so
        ├── original.apk
        └── libs/        (other matching-ABI native libraries, if any)
```

The original APK is kept as a ZIP asset source for the future native
backend. No Unity or Mono libraries are synthesized. The stager only accepts
a Cocos-detected APK and validates ELF32/EM_ARM headers before replacing
existing staging data. Additional `.so` libraries are staged from the same
32-bit ARM ABI, with bounded per-entry extraction and path checks.

The code does not copy expansion files into the generated APK. Best Park's
OBB files are user-managed separately under the wrapper's private
`GuestSharedStorage/obb` via the system Files picker (PR #15).

## Verification

`Pv7CocosGuestPackagerTest` covers native-image extraction, preservation
of the original APK, rejection of non-ARM files *before* overwriting an
existing staging directory, fallback to `armeabi-v7a`, and optional local
testing of Best Park. The latter uses an environment variable, so no game
binary is added to the repository:

```powershell
$env:PV7_COCOS_APK = "C:\Users\Jacqueb\Downloads\08093-Best-Park-v1-2-1.apk"
.\gradlew.bat :app:testDebugUnitTest --tests "app.morphe.manager.domain.pv7.Pv7CocosGuestPackagerTest"
```

## Remaining before any Cocos compatibility claim

1. Register `cocos2dx` as an executable host backend and stage these
   assets when it is selected.
2. Load/relocate `libgame.so` using the shared ARM32 ELF/Dynarmic runtime.
3. Resolve the native system dependencies and initialize the corresponding
   Java/JNI bridge, including the required Cocos classes.
4. Execute ELF constructors, `JNI_OnLoad`, and
   `Cocos2dxRenderer_nativeInit`; then drive `nativeRender`.
5. Route the guest's OBB/data/cache/files APIs into the private shared
   guest folder, and implement Cocos input, audio and asset access.
6. Prove first rendered frame / controls on Android hardware, regression
   test CNR, then publish a properly labeled experimental Cocos module.

**Do not** bypass `Pv7GuestBackend.canBuild` for Cocos until the native
backend is present; staging alone is not enough to run the game.

## Inspecting another Cocos2d-x APK

Run the offline, standard-library-only inspection script:

    python3 scripts/inspect_cocos_apk.py /path/to/game.apk

It checks the ARM32 ELF header and reports DT_NEEDED libraries and
JNI_OnLoad/Java_org_cocos2dx_* exported symbols from the actual guest,
without executing the APK. Python unit tests for malformed and valid
fixtures run as part of the catalog-validation CI workflow.

Best Park 1.2.1 has seven DT_NEEDED dependencies and exports
JNI_OnLoad, nativeInit, nativeRender, lifecycle, keyboard, and
touch handlers. The native Cocos backend must resolve them at runtime.
