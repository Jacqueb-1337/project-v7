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

## Verification and limitations

Stage 4 completed eight Unity constructors on an ARM64-only OnePlus 12R,
then stopped on an unimplemented optional Google blocking-region hook.
Stage 5 compiled and linked successfully, was signed and installed, but the
virtual display lost focus before guest execution. The new handlers are
therefore not runtime-verified. No claim of playable Unity 5 support is made.
The patcher-side audit reports candidate coverage for the existing bundled
ARM64 host; it must be regenerated when the diagnostic native host is
integrated. No native binary is replaced by this patch series.

Next: run stage 5 with a reliable virtual display, review remaining imports
and data symbols, audit JNI calls, and regression-test Unity 4.6 before
replacing the host template.
