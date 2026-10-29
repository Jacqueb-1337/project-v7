# Project V7 release and signing guide

Project V7's **production** Android APK is separate from historical debug previews.
This page documents the release chain and prevents the original Morphe project's publishing
settings from being accidentally reused.

## Release channels

| Channel | APK example | Android application ID | Signing |
| --- | --- | --- | --- |
| Production (v1.33.4+) | `project-v7-patcher-1.33.4.apk` | `me.jacqueb.projectv7` | Private, PV7-controlled production signing key |
| Historical debug previews (before v1.33.4) | `project-v7-patcher-1.33.2-debug.apk` | `app.morphe.manager.debug` | Developer/debug key |
| Future debug previews | `project-v7-patcher-<version>-debug.apk` | `me.jacqueb.projectv7.debug` | Developer/debug key |

**Critical correction:** v1.33.3 mistakenly used `app.morphe.manager`, the **original Morphe Manager package ID**, and could appear as an update to Morphe in Android’s package installer. Do not install v1.33.3 as an update to Morphe. As of v1.33.4, PV7 is independent under `me.jacqueb.projectv7`. The unchanged Kotlin `app.morphe.manager` namespace refers to internal classes and is **not** the installed Android package ID.

**These are different Android packages.** An official production APK does not update a
previously installed `.debug` APK in place. Users can install production alongside
a debug preview and migrate settings manually if supported; do not promise otherwise.
Do not rename an APK to change its real application ID or signing certificate.

## One-time production signing configuration

The repository owner must create and **back up** a private Android app signing key and
a separate OpenPGP signing key. No private key belongs in Git, public CI artifacts, logs,
chats, or a publicly shared download directory.

1. Create a strong private keystore using `keytool -genkeypair -storetype PKCS12`
   with RSA 4096, a long-lived certificate, and a unique alias (for example
   `pv7-production`). Store it **outside the repository**.
2. Record the keystore, alias, and passwords in a secure offline recovery backup.
   If the production signing key is lost, a replacement APK with the same package
   name generally **cannot** update existing installations.
3. Generate a separate OpenPGP signing key and back up its encrypted private key.
   Record its full fingerprint; do not reuse Morphe's release identity.
4. In **GitHub → repository Settings → Secrets and variables → Actions**, configure
   these repository **secrets**:

   | Name | Value |
   | --- | --- |
   | `KEYSTORE_B64` | Base64 of the **complete binary keystore** (not a filename or raw password) |
   | `KEYSTORE_PASSWORD` | Keystore store password |
   | `KEYSTORE_ENTRY_ALIAS` | Signing key alias inside the keystore |
   | `KEYSTORE_ENTRY_PASSWORD` | Entry/key password |
   | `GPG_PRIVATE_KEY` | ASCII-armored OpenPGP private key |
   | `GPG_PASSPHRASE` | OpenPGP private-key passphrase |

5. Configure a non-secret repository **variable** `GPG_FINGERPRINT`
   with the full signing fingerprint. Keep a copy of the **public** key for users
   who wish to verify detached signatures.
6. Test the key locally using `keytool -list -keystore <keystore>` and check that
   the configured alias exists. Record the certificate SHA-256 fingerprint.
7. Use **Actions → Release Project V7 → Run workflow** for an explicit first
   production release, after verifying the production APK will have the expected
   package ID and signing identity.

Do **not** reuse upstream Morphe's keystore or website deployment credentials, and
never replace a working PV7 production key just to resolve a CI error.

### Windows PowerShell: preparing the base64 secret

Run with the **actual private keystore path** and avoid writing the base64 output
into a chat, screenshot, or shell transcript:

```powershell
$repo = 'Jacqueb-1337/project-v7'
$path = 'C:\Secure\ProjectV7\pv7-production.p12'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) |
  gh secret set KEYSTORE_B64 --repo $repo
```

Configure the remaining secrets with `gh secret set` or GitHub's protected settings
interface. Never commit the signing keystore or private-key exports.

## How the workflow operates

- A **pull request** to `main` runs catalog validation and an Android build.
  External forks, or projects without production signing configured, produce
  **debug preview APK artifacts**, not official release updates.
- A push to `main` checks for the complete production signing configuration.
  If keys are absent, it records a notice and **does not publish an APK or updater JSON**.
  A manually requested release without keys fails explicitly.
- With the keys configured, the release workflow runs semantic-release, builds and
  APK-signs the production package, signs the APK with the configured OpenPGP key,
  verifies the detached signature, publishes the GitHub Release, creates an
  attestation, and publishes `app-release.json` only after assets are downloadable.
- Automatic FCM alerts and third-party website deployment are deliberately
  **disabled** until PV7-controlled infrastructure exists.

The in-app updater selects production APKs only for production installations and
debug preview APKs only for debug installations. Android verifies the installed
APK's signing identity at installation time. The additional `.asc` file is a
detached signature for independent verification, **not** an assertion that the
current in-app updater independently verifies OpenPGP signatures.

## Existing release tags

Older pre-v1.33.3 artifacts were experimental `-debug.apk` downloads. The v1.33.3 production APK was published using Morphe’s `app.morphe.manager` package ID by mistake. It has been superseded by the unique PV7 production ID introduced in v1.33.4.
These are preserved for provenance and testing, but they should not be presented as
production updates. The older root `app-release.json` was inherited from Morphe
and must not be used as a production update feed until PV7 has published an actual
signed release.

## Troubleshooting

- **Import GPG key** fails: inspect only the step name and non-secret error messages.
  Confirm the private key and passphrase are correctly configured; never paste either
  into a public issue.
- **Keystore invalid / key alias missing**: verify the full keystore was base64
  encoded (not its path), that it opens with the store password, and that the entry
  alias and password match.
- **`packageRelease` fails with keytool errors**: a corrupt or wrong keystore can
  look like an Android build failure even if unit tests and lint passed.
- **APK refuses to install as an update**: compare package name, versionCode and
  signing certificate fingerprints. Do not circumvent Android signature checks.
- **No release appears**: verify semantic-release recognized a version-worthy
  commit, and that all required credentials were configured before the run.

Related issues: [release pipeline](https://github.com/Jacqueb-1337/project-v7/issues/5).
