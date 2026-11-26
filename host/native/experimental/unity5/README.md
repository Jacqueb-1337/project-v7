# Experimental Unity 5 native runtime patches

These are the exact incremental C++ changes used in the Guns n Spurs 1.3.3
ARM32 Unity 5.6.1f1 experiments. The native runtime is developed separately in
the copsnrobbers repository. This series preserves our changes here for
review and porting; it does not include the separate native repository or any
original game binaries. Apply the patches in numerical order from the root of
a matching native checkout, using git apply --check first. The experimental
baseline (LF-normalized shared_guest_linker.cpp SHA-256) is:

83c46fa7e82abd6eb69db8c47ac61dd574fd7333d59a0b2f8d7a553b788bdfd6

If the source differs, review and port the hunks rather than applying them
blindly. The series round-trips byte-for-byte against the saved stage 3, 4
and 5 sources after newline normalization.

- 01: ELF dynamic constructor discovery, executable segment validation, and
  fingerprint-gated legacy CNR probes.
- 02: compare ARM register state rather than repeated PC alone; retain the
  execution budget.
- 03: 16 additional import handlers (math, string, optional instrumentation,
  EGL and native-window queries).
- 04: select lifecycle calls by actual registered JNI method descriptors;
  initialize UnityWebRequest when registered and use the indexed surface ABI.
- 05: reference-counted guest native-window proxy, with native pin transfer
  on surface replacement and cleanup on shutdown.
- 06: ARM EABI syscall dispatch for guest PID/TID and virtual affinity masks;
  unknown calls return ENOSYS rather than being forwarded to the host.

## Verification and limitations

Stage 4 completed eight Unity constructors on an ARM64-only OnePlus 12R,
then stopped on an unimplemented optional Google blocking-region hook.
Stage 5 retest on a persistent isolated display passed all three ELF constructor
arrays: 329 executed, 3 null entries. Unity JNI_OnLoad returned JNI 1.6,
registered 20 natives, and initJni, nativeFile and nativeInitWWW returned.
The next blocker was the missing legacy nativeSetDefaultDisplay registration.
The original DEX confirms Unity 5 uses nativeRecreateGfxState(int, Surface),
no separate nativeResize or nativeSetDefaultDisplay, and initializes
UnityWebRequest after WWW. Stage 6 adapts the lifecycle to those descriptors;
its ARM64 build compiled and on-device testing passed all constructors, JNI_OnLoad,
initJni, nativeFile, nativeInitWWW and nativeInitWebRequest. The indexed surface
method was selected and entered; it stopped at ANativeWindow_acquire. No claim
of playable Unity 5 support is made.
The patcher-side audit reports candidate coverage for the existing bundled
ARM64 host; it must be regenerated when the diagnostic native host is
integrated. No native binary is replaced by this patch series.

Stage 7 passed the native-window acquisition/release sequence (references
2 then 1), nativeRecreateGfxState, nativeResume and nativeFocusChanged.
The first nativeRender entered, ran two pthread_once initializers, and
stopped on syscall. Disassembly of its call site shows ARM EABI syscall
241 (sched_setaffinity), not the ARM64 syscall number. No frame rendered yet.

Stage 8 compiled and linked with a guest-scoped syscall dispatcher. Affinity
masks are virtual state for cooperative guest threads, not host CPU pinning.
Runtime verification remains pending.

Next: verify stage 8, audit remaining imported data and JNI calls, and
regression-test Unity 4.6 before replacing the template.

## Stage 9 and Stage 10 runtime verification

Stage 9 (patch 07) registered 91 additional existing GLES dispatch handlers for dynamic lookup and added bounded lookup-miss diagnostics. On-device testing passed the previous glGetString null branch but exposed another at glGetShaderPrecisionFormat; the diagnostic log identified 33 graphics lookup gaps.

Stage 10 (patch 08) adds 32 marshaling handlers with capability checks. KHR_debug is withheld pending guest callback support. The ARM64 build, APK signature and 16 KiB ZIP alignment passed. A cold launch on the isolated OnePlus virtual display reported zero unresolved graphics lookups, performed framebuffer probes, then exhausted the 16,000-slice nativeRender budget at guest PC 0x031c57d8 without reporting a guest fault. No completed render or playable support is claimed. Seven non-graphics lookup misses remain, including optional Mono AOT metadata probes. Next investigate progress through Mono initialization and make the startup watchdog progress-aware. Production wrapper and Unity 4.6 regression testing remain separate.

## Stage 11 through Stage 13

Patch 09 implements a progress-sensitive startup execution budget (up to one million slices, sampled 45-second wall limit). Stage 11 completed nativeRender after 314,998 slices and subsequent render calls returned. Register changes are a heuristic, not proof of semantic progress.

Patch 10 retries failed GLSL ES 100 shaders with the two observed malformed header prefixes repaired. The Unity splash was visually reported on Stage 12. Stage 13 logged 12 shader recoveries and 12 remaining failures (extension directives after precision declarations, including repeated attempts). No game scene or playability is verified.

Patch 11 adds sampled render-thread wait ownership and periodic worker snapshots. Stage 13 identified guest worker 33 holding recursive mutex 0x055f9cb4 while main render polls it. Worker samples repeatedly show 0x031be15c, corresponding to the Mono mempool growth loop assuming the observed Mono base of 0x03000000. Investigate allocator operands and provenance before modifying synchronization. No lock bypass is implemented. Patches 09-11 round-trip against saved stage sources with LF normalization. All three isolated ARM64 builds compiled, signed and installed; production CNR regression is not tested.

## Stage 25 import audit triage

The preserved Stage 25 dispatch snapshot contains 589 candidate names, 561 of which also appear as NUL-terminated byte strings in the released host. The other names are advisory source-only evidence; the optimizer may remove strings, and source evidence alone does not verify the compiled dispatch. Of 28 previously reported potential function gaps in Guns and Spurs 1.3.3, 18 have explicit source comparisons. Ten remain outside those comparisons:

- abort, exit, _exit: termination diagnostics and guest halt, not normal return handlers.
- __assert2, __cxa_begin_cleanup, __cxa_call_unexpected, __cxa_type_match: assertion/exception paths requiring review.
- getnameinfo, realpath, statfs: no explicit matching bridge comparison found.

Eight data imports remain under review. The source initializes __page_size and __stack_chk_guard; this is not evidence of correct handling for the other six. Do not implement no-op stubs simply to reduce the audit count.
