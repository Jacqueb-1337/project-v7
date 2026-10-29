# Generic PV7 guest storage

Each PV7-generated wrapper gets an additional, **user-controlled Storage Access Framework (SAF) provider** named **PV7 Game Storage**. It uses the provider authority `<installed-wrapper-package>.gamefiles`, so multiple games remain independent.

In Android's **Files** app, open **Browse** and select **PV7 Game Storage**. Four virtual directories appear:

| Files folder | Actual backing directory | Purpose |
| --- | --- | --- |
| `obb/` | `getObbDir()` → `Android/obb/<installed-wrapper-package>/` | Actual expansion files for the wrapper's package |
| `data/` | `getFilesDir()/GuestSharedStorage/data/` | User-managed guest saved data |
| `cache/` | `getFilesDir()/GuestSharedStorage/cache/` | User-managed guest cache files (persistent until deleted) |
| `files/` | `getFilesDir()/GuestSharedStorage/files/` | Additional user-managed game files |

On Android 11+, ordinary file managers cannot freely browse another app's `Android/obb` or `Android/data` directories. The SAF root is the sanctioned way to grant the user access to **these wrapper-owned areas**, without requesting all-files access. It does not expose the wrapper's databases, credentials, preferences, or unrelated internal files.

## OBB filenames and identity

Traditional Google Play expansion files use names like:

- `main.<versionCode>.<wrapperPackage>.obb`
- `patch.<versionCode>.<wrapperPackage>.obb`

The app-visible package after wrapping is the *wrapper's* installed package. For Best Park v1.2.1 the original APK's version code is **16**, but the original game uses an Android expansion-file helper that assembles its OBB name from `getPackageName()`. A future Cocos2d-x backend must ensure the guest's expansion-file APIs consistently resolve to **the wrapper's actual package / OBB directory**, including when older native code uses legacy paths. The presence of an `obb` folder alone does **not** solve the Cocos2d-x emulation or expansion-file download requirements.

PV7 deliberately never reaches into another application's OBB or data directory. Obtain a lawful copy of the game's expansion file and use Android's system picker to import it through **PV7 Game Storage → obb**. It is stored directly in the wrapper's actual `getObbDir()`.

## Engine integration

The storage provider itself is **engine-independent** and is included in every generated wrapper, including CNR. Existing CNRMods SAF support continues separately as `<package>.documents`.

To consume the private guest `data/`, `cache/`, or `files/` trees, an engine backend must route its guest Java/JNI or native filesystem accesses into those paths. An engine that queries Android's standard `getObbDir()` should resolve to the host package's `obb/` location; hard-coded legacy absolute paths need a backend-specific rewrite (not arbitrary access to the original application's directory).

This is a storage foundation, **not** a declaration that every APK is supported or that a Cocos2d-x game is playable.

## Maintainer build

The provider source lives at `host/src/app/projectv7/wrapper/Pv7GameStorageDocumentsProvider.java`. `scripts/embed_storage_provider.py` compiles it using the Android SDK and adds it as `classes2.dex` in the versioned host template. The manager injects the Android DocumentsProvider declaration for every generated wrapper, using the actual wrapper package as its authority.

The provider uses canonical path checks and refuses to delete/rename virtual roots or escape its dedicated folders (including through symlink traversal). Changes inside the Files UI require user-selected SAF permissions.

**Uninstalling a wrapper deletes its app-private data and typically its external app-specific OBB files. Keep backups of saves and legally obtained expansion files.**
