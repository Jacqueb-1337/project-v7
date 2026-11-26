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

Next: implement guest-scoped syscall dispatch, audit remaining imported data
and JNI calls, and regression-test Unity 4.6 before replacing the template.
