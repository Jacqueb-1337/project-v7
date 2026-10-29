# Generic PV7 guest storage

Every generated Project V7 wrapper exposes a **PV7 Game Storage** entry in
Android's Files app. All four categories belong to the **same app-private
shared guest directory**, and all are equally editable through the system
Storage Access Framework (SAF) picker:

```text
<app-private filesDir>/
``` GuestSharedStorage/
    ``` obb/
    ``` data/
    ``` cache/
    ``` files/
```

| Files folder | Backing directory |
| --- | --- |
| `obb/` | `getFilesDir()/GuestSharedStorage/obb/` |
| `data/` | `getFilesDir()/GuestSharedStorage/data/` |
| `cache/` | `getFilesDir()/GuestSharedStorage/cache/` |
| `files/` | `getFilesDir()/GuestSharedStorage/files/` |

Users can import, read, write, create, rename and delete files in all four
areas from **PV7 Game Storage** in Android's Files app. The provider gives
the selected Files client scoped access to these purpose-built directories
without root or unrestricted storage permissions. It does not expose the
wrapper's settings, databases, keys, or unrelated private data.

Each game has its own tree: the provider authority is the generated wrapper's
package name followed by `.gamefiles`.

## Private OBB: NOT Android/obb

The `obb/` folder is **not** `Android/obb/<package>` and is **not**
backed by Android's standard `Context.getObbDir()`. It lives beside
`data/`, `cache/` and `files/` inside `GuestSharedStorage`.
This is a deliberate PV7 layout choice.

Android games can request OBBs using `getObbDir()`,
`getPackageName()`, legacy external storage paths, or direct native
filesystem calls. A PV7 compatibility backend must redirect the game's
OBB lookups into `GuestSharedStorage/obb/`. Without that redirection, the
game can still look in the conventional external OBB directory.

**The SAF storage provider does not implement the native/runtime path
redirection.** In particular, the separate Cocos2d-x backend for
Regular Show: Best Park is still unfinished. Adding an OBB file here by
itself does not make the game launch or find its expansion data.

Files are never silently copied from another installed application's
protected data or OBB directory. Obtain a lawful copy of the expansion file
and import it into the private OBB folder through the system picker. The
runtime backend must also account for the original game's expected OBB
filename and version code.

## Safety and lifecycle

- The DocumentsProvider is limited to `GuestSharedStorage` and uses
  canonical path checks, including symlink-safe recursive deletion.
- The four category roots cannot be deleted or renamed in the Files UI.
- Existing CNRMods DocumentsProvider support remains separate.
- This layout is replacing the original unmerged PR #15 design that used
  `getObbDir()`. No migration from conventional Android/obb is included.
- Uninstalling a game wrapper normally deletes its app-private data,
  including these folders. Back up saves and expansion files before uninstalling.

## Developer setup

The provider implementation is
`host/src/app/projectv7/wrapper/Pv7GameStorageDocumentsProvider.java`.
`scripts/embed_storage_provider.py` compiles it to secondary DEX in
PV7's host-template APK. The manager registers the provider authority
based on each generated wrapper's actual application ID.
