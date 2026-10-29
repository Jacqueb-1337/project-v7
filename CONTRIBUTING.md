# Contributing to Project V7

Project V7 aims to preserve older Android apps and games on modern 64-bit-only devices. Game profiles, compatibility patches, fixes, tests, and documentation are welcome, including work in progress.

**Read first:** [Project V7 overview](README.md) | [Catalog reference](catalog/README.md) | [Issues](https://github.com/Jacqueb-1337/project-v7/issues)

## Quick start: add a game

1. **Check existing issues and PRs.** For a new game, consider opening an issue first. Include the package name, version, architecture (such as `armeabi-v7a`), engine/version if known, and target Android version.
2. **Fork the project** and branch from `Jacqueb-1337/project-v7:main`. Our contribution target is **main**, not Morphe's `dev` branch.
3. **Create an app entry** at `catalog/apps/<package-id>.json`. Supply a display name, real package ID and version, plus valid authorized download/icon URLs if available. Do not upload copyrighted APKs without permission or leave `YOUR_USERNAME` placeholders.
4. **Add a game module if a patch is needed** at `catalog/modules/games/<package-id>/<module-id>/module.json`. General, reusable modules go in `catalog/modules/general/<module-id>/`. The module `id` must be unique.
5. **Add a profile** at `catalog/profiles/<package-id>/<version>.json`. Set `"tested": true` **only** after successfully rebuilding, installing and running that exact app/version. Otherwise set it to `false`. A successful CI APK build is not runtime proof.
6. **Register files** in the appropriate lists in `catalog/index.json`. Keep all existing entries unless their removal is intentional and explained.
7. **Run validation** from the repository root: `python3 scripts/validate_catalog.py`. There are no Python package dependencies.
8. **Open a pull request** against `Jacqueb-1337/project-v7:main`. Describe tested devices, Android and app versions, what works, what fails, and include reproducible logs where possible.

The CI workflow validates every PR automatically. A contributor should not have to touch the release workflow to add a game.

## Important: file paths are not module IDs

`catalog/index.json` contains paths **relative to `catalog/`**. For example:

```json
{
  "schemaVersion": 1,
  "modules": [
    "modules/general/core-compat/module.json",
    "modules/games/com.example.game/example-compat/module.json"
  ],
  "profiles": ["profiles/com.example.game/1.0.json"],
  "apps": ["apps/com.example.game.json"]
}
```

The module manifest at `catalog/modules/games/com.example.game/example-compat/module.json` uses a short **ID**:

```json
{
  "id": "example-compat",
  "name": "Example Game compatibility",
  "version": "1.0.0",
  "kind": "game",
  "description": "Work-in-progress compatibility for Example Game",
  "targets": {
    "packageIds": ["com.example.game"],
    "versions": ["1.0"],
    "abis": ["armeabi-v7a"]
  },
  "dependencies": ["core-compat"],
  "operations": [],
  "payloadFiles": []
}
```

**This example is a stub, not a working compatibility fix.** Implement and test actual operations or payloads before claiming that game compatibility works.

A corresponding, **untested** profile recommends the IDs, not the paths:

```json
{
  "id": "example-game-1.0",
  "packageId": "com.example.game",
  "versions": ["1.0"],
  "abis": ["armeabi-v7a"],
  "engines": ["unity"],
  "tested": false,
  "recommendedModules": ["core-compat", "example-compat"],
  "blacklist": [],
  "notes": "Experimental. Not yet tested on-device."
}
```

**Incorrect:** `"recommendedModules": ["modules/general/core-compat/module.json"]`.

**Correct:** `"recommendedModules": ["core-compat"]`.

Dependencies (`dependencies`), blacklist rule IDs (`moduleId`), and conditional blacklist references (`whenAnySelected`) also use module IDs.

## Test and validate before submitting

```bash
python3 scripts/validate_catalog.py
```

The script checks JSON structure, indexed files and their paths, files forgotten in the index, unique module/profile/app IDs, referenced module IDs, dependencies and dependency cycles, missing module payloads, and obvious placeholder app URLs. It warns about game modules with no operations or payloads.

On pull requests, CI also compares the catalog index against `main`. Removing an existing module, profile, or app entry without explanation fails the check.

For an **intentional removal**, add the removed index path and a meaningful reason to `.github/catalog-removal-approvals.json`:

```json
{
  "modules/general/obsolete/module.json": "Superseded by new-module; migration explained in this PR"
}
```

Only list paths actually removed by this PR. Stale approvals cause validation to fail. Maintainers will review the reasons. Removing an index entry does not delete its file, so also remove obsolete files if appropriate.

**Validation is not game testing.** Report whether you actually ran the rebuilt game, where it fails, whether graphics/audio/input work, and whether performance is usable. Mark experimental profiles as untested until then.

**Important for forks:** the patcher's current catalog loader points to the official `Jacqueb-1337/project-v7` catalog. Simply compiling your own fork does not make it load your fork's new remote catalog. To verify a change, make sure you're actually exercising your modified catalog, not only building the unchanged patcher.

## Review guidelines

- Keep one game or independent fix per PR.
- Preserve unrelated modules, profiles, and supported-app entries.
- Keep app-specific work in game modules. Put reusable work in general modules.
- Do not alter signing, release automation, or updater settings for a catalog-only change.
- Be clear about what was measured, tested, and what remains experimental.
- Do not distribute proprietary APKs or assets without permission.

## Questions and collaboration

[Open an issue](https://github.com/Jacqueb-1337/project-v7/issues/new) and describe the difficulty. You can mention someone with `@username` in public GitHub issues or pull requests; GitHub does not provide regular private direct messages.
