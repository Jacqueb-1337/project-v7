# CNR compatibility ownership and performance baseline

## Separation of concerns

- **PV7 core:** ARMv7-to-ARM64 translation (Dynarmic), guest memory, JIT cache invalidation, guest thread scheduling, Android JNI/POSIX bridges, generic resource profiles and cache/download support. These improvements must not become CNR-only patches.
- **Generic Unity 4.6 compatibility:** engine-family matching and reusable Unity 4.6 policy. Do not embed game-specific addresses in this module. This module is included in CNR's tested recommended profile.
- **CNR compatibility:** original CNR package identity, `CNRMods` storage aliases, CNR's input native-call address, a **binary-version-specific** Unity 4.6 signal-init instruction patch, CNR-managed mod bootstrap, and the verified CNR ARM64 host binary. The CNR-specific host is overlaid on top of the generic PV7 template only when this game module is selected.
- **Managed CNR mods:** multiplayer and map features (CNRMod), the in-game mod browser (CNRModManager), CNR settings, and graphics settings. These stay separate C# assemblies; they are **not** native compatibility modules.

## Verified versions

| Mod | Version |
| --- | --- |
| CNRMod | 4.2.56 |
| CNRModManager | 1.6.6 |
| CNRSettingsMod | 3.1.111 |
| GraphicsSettings | 0.1.1 |

`scripts/sync_cnr_recommended.py --source-mods <path-to-cnr-revived-web/mods>`
pins versions and checks each canonical and versioned release DLL has identical SHA256.
`scripts/validate_cnr_recommended.py` checks the catalog, dependencies, installed bootstrap policies, native host bytes and the generic template separation.

The CNR host is stored as `lib/arm64-v8a/libpv7host.so.gz` (11,370,583 bytes, decompressed 55,681,344 bytes). `Pv7WrapperAssembler` inflates this file when chosen and writes it as `lib/arm64-v8a/libpv7host.so`. Decompressed SHA256:
`0396656c190c8f06fe195c5336453ab4ee56b5e3d4ccfb481af408ef02cd7ae9`.

This exact native SHA256 was checked against the successful Project V7 build installed on the user's phone on November 23, 2026. It contains the cooperative `render-worker-wait` and 120-second no-progress guard that prevented Shipment MW's large CNRMod DLC preparation from stopping the guest.

CNR's `CNRMods` bootstrap uses `preserveCustom=true`, so the recommended versions seed absent mods without overwriting any user's existing custom/modded binaries. The complete `repo-sources.txt` fallback remains separate.

Catalog downloads use unchanged module-manifest text as a payload cache key. Every new release that changes payload bytes must bump the module version and update checksums; otherwise an installed patcher may reuse old cached payload bytes.

## Scope and remaining improvements

- One Shipment MW spawn on the verified PV7 host passed an on-device forward-movement/jump smoke test without falling through the ground. The full 31-spawn route and collision validation remains open.
- CPU usage was approximately one full host core, mostly the `CNR64-Harness` thread. GPU utilization about 9%, process PSS about 1.47 GB with about 0.64 GB swap. CPU sensor about 91 C, skin sensor 44 C. The test process was stopped to cool the phone.
- The current code uses one `Dynarmic::A32::Jit` instance with a cooperative guest-thread scheduler; guest threads are not currently each run on distinct host CPU cores. Parallel JIT compilation may reduce stalls but not necessarily average game-loop cost; fully parallel guest execution would require per-thread execution contexts, shared memory coherence, code-cache and Mono-JIT invalidation safety, guest mutex/condition emulation and deterministic regression testing.
- Recommended next A/B: profile translated block compilation, guest scheduling, cache misses/flushes and slow frames; then test background compilation or native work partitioning in an isolated build. Preserve the CNR content heap and cooperative worker-wait behavior. ARM64 block linking is currently disabled by a development flag in the working CNR native build, so test enabling it in isolation only.
- Current local Android Gradle unit tests are blocked by unauthorized access (HTTP 401) to the upstream `app.morphe:morphe-patcher:1.15.0` private Maven package. Offline hash/catalog validation is green, but a full APK assembly/unit test and install have **not** been run for this new catalog release. Do not claim a new APK was tested on-device.

## Future refactors

CNR-specific addresses and path aliases are already owned by the CNR module. Some CNR-specific scheduling call-site recognition still appears inside PV7 core; a generic policy/callback extension would make that configurable for other games, but should be treated as a later compatibility refactor to avoid destabilizing the known-good CNR guest. Do not move exact CNR opcodes into a generic Unity module.
