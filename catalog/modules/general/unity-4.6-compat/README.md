# Unity 4.6 compatibility (experimental)

This general module depends on `core-compat`, installs a versioned `compat.properties`
payload, and enables the private path-rewrite policy in PV7's native runtime.

**Verified baseline: Cops N Robbers 3.0.2, Unity 4.6.1f1 ARMv7 only.**
Other Unity 4.6.x games, including Regular Show, are **unverified**.

CNR's legacy signal-handler patch uses a binary-specific ARM instruction at an
absolute address, so it remains in `cnr-compat`. CNRMods paths, bootstrap, input
calibration and game-specific device properties stay in CNR as well.

This module does not guarantee that another Unity game builds, starts, renders,
accepts input or runs at a playable framerate.
