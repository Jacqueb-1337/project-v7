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

PV7 now emits package-specific path.alias.N entries into the generated
wrapper compatibility properties, mapping legacy OBB, data, cache, and
files paths to their private GuestSharedStorage folders. The existing
native path-normalization interface consumes those aliases for its
supported guest filesystem calls. This is shared-wrapper configuration,
not a Best Park-specific patch.

Pv7GuestStoragePaths and Pv7GuestStorageBridge are embedded into the
host template's secondary DEX. They provide common Java/JNI entrypoints
for rewriting returned guest paths and resolving existing expansion files.
The resolver also rejects directory traversal and avoids hijacking paths
belonging to other packages.

The ARM64 guest JNI dispatcher now also intercepts common Context storage
directory getters, returns real guest java.io.File handles, supports
File.getAbsolutePath(), getCanonicalPath(), getPath(), existence tests
and related getters, and redirects returned string paths. Standard expansion
helpers with a no-argument String return can discover the matching
main.<version>.<guest-package>.obb under the shared private obb/ directory.
The expected guest version is recorded in compat.properties when provided
by the wrapper assembler. None of this depends on a particular game ID.

The native JNI hook is reproducible from the template source:
- python scripts/integrate_guest_storage_native.py <native-wrapper-directory>
- Build the native ARM64 library from that wrapper source
- python scripts/embed_storage_native.py --native-so <built-arm64-library>

The rebuilt ARM64 host is embedded into the template; the x86_64 host
remains unchanged. Both C++ guest-JNI tests and Java path tests exercise
the respective resolvers, and Android native/manager builds were validated.
Limitations: PV7 cannot automatically execute arbitrary legacy app Java
bytecode, so opaque helpers not matching the supported JNI signatures
may still need compatibility handling; operations outside the shared
normalization/JNI paths also require coverage. On-device end-to-end
execution and Cocos first-frame rendering are not yet verified.
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
