# Project V7: independently versioned compatibility modules

The patcher now distinguishes **general**, **game**, **backend**, and **runtime**
modules. Runtime and backend packages live in the existing remote catalog, not in
an app update. A publisher can change their `version`, native payload files,
compatibility targets and SHA-256 hashes without publishing a new Manager APK.
The Manager downloads changed catalog payloads when preparing a patch.

## Selection

- Engine and engine version are read from the original guest APK.
- A matching manifest with `suggestedWhenMatched: true` is recommended. If
  several variants share `selectionGroup`, the best matching and newest
  semver wins automatic selection. A tested game's recommended modules still
  apply.
- **All variants remain visible for manual selection**, including mismatched
  versions. Unsupported versions are clearly marked untested. A user can select
  multiple modules. Explicit conflicts, missing dependencies or duplicate
  output paths block the build; `assets/pv7/compat.properties` is intentionally
  layered.
- A backend's presence does not override host/guest packaging limitations.
  The currently shipped production template still only packages compatible
  legacy Unity 4.6 ARM32 Mono guests. The experimental Cocos Dynarmic renderer
  remains a separate unmerged development branch.

## Publishing a standalone backend or runtime

Create a manifest under `catalog/modules/general/<id>/module.json`, register
its **path** in `catalog/index.json`, and place files beneath its `payload/`
directory. A runtime policy module may ship only
`assets/pv7/compat.properties`; a host-native backend can ship
`lib/arm64-v8a/libpv7host.so` and `lib/x86_64/libpv7host.so`, provided the
libraries obey the current host JNI/ABI contract. Unlike a stub module, a
payload must actually implement or modify the declared behavior.

Use `kind: "runtime"` or `kind: "backend"` with semver `version`,
`targets.engines`, `targets.engineVersions`, `targets.abis`, and optional
`selectionGroup`. Every remotely downloaded runtime/backend payload requires
a corresponding `payloadSha256` entry; mismatches reject the entire remote
catalog refresh and leave the last valid cache. Do not use untrusted catalogs,
as a trusted native module can replace code in generated wrappers. Existing
official catalog remains the default; selecting a contributor fork is explicit.

## Updates and repatching

New PV7 wrappers contain:
- `assets/pv7/module-lock.json`: exact module IDs, versions and kinds,
  original guest identification, and a SHA-256 of the retained original.
- `assets/pv7/original-guest.apk`: an exact byte-for-byte copy of the supplied
  original game APK. This intentionally increases the wrapper file size.

The version check fetches only remote manifest metadata, compares versions
with known wrappers built by that Manager installation, and uses Morphe's
existing patch-update notification channel. The same check runs on opening the
Manager and on the configured periodic update schedule when background update
notifications are enabled. In the compatibility selector, affected modules
show their old and available versions.

For an upgrade, select either the original game APK or the installed/saved PV7
wrapper that contains these two files. PV7 verifies the embedded APK SHA-256
and original AndroidManifest, then reuses it as patch input and rebuilds using
the latest selected module payloads. Existing wrappers produced before the
embedded-original format **cannot** have the original reconstructed reliably
and require an original APK supplied by the user. Never try to patch the
installed `base.apk` in place.

Android replaces an installed game only when its package name and signing
certificate match. A successful PV7 rebuild alone does **not** guarantee that
its signature matches the installed wrapper. Use the same persistent signing
identity; otherwise Android will reject the update, requiring a deliberate
uninstall/reinstall that may lose app data. User-managed OBB/saves in private
PV7 folders are not automatically migrated by the repatch operation.

This is the **catalog, integrity, discovery, notification and repatch layer**.
It is not a claim that runtime native code can be hot-swapped into an already
running installed game, that a brand-new engine is supported without its
required host contract, or that the Cocos diagnostic is a production backend.
