# Shared PV7 guest supervisor

Every game generated through the supported PV7 wrapper backend inherits the same
supervisor from the bundled host template, including without a game-specific
module. No supervisor module selection is required.

## Launch and recovery

The host displays a PV7 splash and startup progress, offers per-game launch
configuration before starting the guest, checks the guest heartbeat, and
provides a recovery action when the guest stalls. Guest surface loss pauses the
runtime and attempts to rebind it instead of treating it as a guest exit. Older
native modules without a newer JNI method use the existing fallback behavior.

The ongoing supervisor notification provides a return-to-game shortcut. It
depends on the user's Android notification permission. It is not a foreground
service and cannot prevent Android from killing the process under memory pressure.

## Overlay and cache controls

The performance overlay is opt-in and its metrics, 4–32 sp font size, placement,
and optional text background are saved in that game's preferences. The default
is 14 sp with a background. The live preview uses the same settings as the
in-game overlay.

The JIT code cache supports 16, 32, 64, 96 or 128 MiB. On ARM64 the primary
dispatch and secondary victim caches are also configurable. Selecting the
module default removes the per-game override; invalid native settings fall
back to defaults. x86_64 currently uses a fixed dispatch table, so the ARM64
dispatch/victim controls do not change that backend's table size.

## Module boundaries

Generic ARM64 and x86_64 native libraries must be built without CNR-only heap
or experimental build flags. The same Java supervisor is included in both
generic and game-specific wrappers. A compatibility module may supply its own
native library and defaults, but it does not own the supervisor UI.

CNR module 1.0.12 uses the exact ARM64 native library from the successful
asynchronous WWW startup experiment (SHA256
`25e8841edce5d5ea6bcfa8dd7d99f64db32e5fffb9cdd273bf86ca5a5307e571`).
Only the CNR module enables `pv7.cnrAsyncWww` and sets the CNR-specific 64 MiB
default. The native policy also checks the original guest package and module ID.
The exact cache-flush experiment remains disabled.

## Rebuilding the template

In the native wrapper source checkout, build the generic targets for ARM64 and
x86_64 with `PROJECTV7_CNR_CONTENT_HEAP=OFF`, no selected compatibility modules,
and all development/experimental build switches off. Then run:

```sh
python scripts/embed_supervisor_host.py --arm64 path/to/arm64/libpv7host.so --x86-64 path/to/x86_64/libpv7host.so
python scripts/verify_storage_abis.py
python scripts/validate_cnr_recommended.py
python scripts/validate_catalog.py
```

The embed script compiles the Java host sources and preserves the existing
secondary storage-provider DEX and manifest. The assembler adds the recovery
activity and notification permission to generated packages. Keep the CNR native
payload separate; never use it as the generic ARM64 library.

On-device testing of the full updated host is required before claiming generic
guest compatibility or measured performance improvements.
