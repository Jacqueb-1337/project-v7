# Project V7 - Android 32-bit Compatibility Patcher

<p align="center"><img src="docs/project-v7-icon.png" alt="Project V7 Android 32-bit compatibility patcher logo" width="180"></p>

Project V7 is an Android APK patcher and compatibility project for getting older 32-bit Android apps and games running on newer 64-bit-only Android devices.

It started with one very specific problem: Cops N Robbers 3.0.2 is a 32-bit ARMv7 Unity game, and modern 64-bit-only phones cannot run it normally. Project V7 grew out of the work to make that game usable again without turning the whole phone into an old Android environment.

The long-term goal is to make the same approach reusable for other older Android apps and games.

## Android 32-bit compatibility

Project V7 is designed for cases such as:

- running 32-bit Android apps on 64-bit-only Android phones and tablets
- running older ARMv7 / armeabi-v7a Android apps when the device no longer provides native 32-bit app support
- preserving older 32-bit Unity Android games
- applying app-specific compatibility fixes without hard-coding every supported game into the patcher

Project V7 is not a full Android emulator or virtual machine. For apps that need it, Project V7 builds a 64-bit host around the original 32-bit app and applies compatibility modules for the app, its engine, and the Android behavior it expects.

## Current status

Project V7 is still early, but the full patching path is working.

Cops N Robbers 3.0.2 is the first tested app. Its compatibility profile is included in the Project V7 catalog.

The supported-app list is loaded from this repository. It is not hard-coded into the Patcher APK. That means support for another app can be added through the catalog without requiring everyone to install a new version of the patcher.

## Download

Download the current Project V7 Android Patcher from the [Releases page](https://github.com/Jacqueb-1337/project-v7/releases/latest).

For supported apps, Project V7 can use:

- an APK you already have
- an installed copy of the app, where supported
- a source APK linked by that app's catalog entry

For Cops N Robbers, the "Help me find an APK" option points to the tested 3.0.2 source APK published in this repository's releases.

## How Project V7 runs 32-bit Android apps

Project V7 does more than change a manifest or rename an APK.

For apps that need it, the patcher builds a 64-bit host around the original 32-bit app and applies compatibility code for the app, its engine, and Android behavior it expects.

The exact work is controlled by compatibility modules and profiles in the catalog. A game can have its own fixes without those fixes being baked into the patcher itself.

A normal patch looks like this:

1. Open Project V7 and choose a supported app.
2. Choose the installed app or select an APK.
3. Project V7 detects the app and matches it with a tested compatibility profile when one is available.
4. The required compatibility modules are selected.
5. Project V7 builds the patched APK.
6. Install the result on the target device.

## Repository-defined app support

The catalog lives under `catalog/`.

Important parts are:

- `catalog/index.json` lists the apps, profiles, and modules available to Project V7.
- `catalog/apps/` contains the entries shown in Supported Apps, including names, download links, and icons.
- `catalog/profiles/` describes tested app versions and the compatibility modules they use.
- `catalog/modules/` contains reusable compatibility modules and app-specific fixes.

Adding a new supported app generally means adding its app entry, profile, and any modules it needs, then adding those files to the catalog index.

A catalog-only change does not require a new Patcher APK.

## Cops N Robbers 3.0.2 Android compatibility

Cops N Robbers 3.0.2 is currently the main test case for Project V7.

Package:

`com.joydo.minestrikenew`

Tested source versions:

- 3.0.2
- 3.0.2-cnr1

The Project V7 catalog currently recommends the common compatibility layer and the CNR-specific compatibility module for this version.

## Frequently asked questions

### Can Project V7 run 32-bit Android apps on 64-bit-only phones?

For supported apps, yes. Project V7 provides an app-specific compatibility path for running original 32-bit Android code on newer 64-bit-only Android devices.

### Does Project V7 support ARMv7 / armeabi-v7a Android apps?

ARMv7 / armeabi-v7a apps are a primary compatibility target, but support is app-specific. Project V7 does not automatically make every 32-bit APK compatible.

### Does Project V7 support Cops N Robbers 3.0.2?

Yes. Cops N Robbers 3.0.2 is the first tested app and the main compatibility test case for Project V7.

## Contributing

Pull requests for new app profiles, compatibility fixes, and improvements to the patcher are welcome.

If you are adding another app, keep app-specific behavior in that app's module when possible. Reusable fixes should go into a general module so another app can use them later.

Please include enough information to reproduce what you tested, including the app version, package name, CPU architecture, and Android version.

## Credits

Project V7 is a modified fork of [Morphe Manager](https://github.com/MorpheApp/morphe-manager).

Morphe provided much of the Android manager foundation this project started from, including the app UI, APK patching workflow, installer support, and project structure. Project V7 is a separate project with its own name, purpose, catalog, and compatibility work. It is not an official Morphe release and is not affiliated with or endorsed by the Morphe project.

Morphe itself is built on work from [ReVanced Manager](https://github.com/ReVanced/revanced-manager) and [Universal ReVanced Manager](https://github.com/Jman-Github/Universal-ReVanced-Manager). Those projects deserve credit for the work that made the manager this fork started from possible.

The original Morphe license and notice files are kept in this repository.

## License

Project V7 is distributed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for the full license.

The additional Morphe conditions under GPLv3 Section 7 are preserved in [NOTICE](NOTICE). In particular, modified versions must be clearly identified as different from Morphe, and the Morphe name, logos, and trademarks cannot be used as Project V7 branding.

"Project V7" is the name of this fork. References to Morphe in this README are for attribution and license compliance.
