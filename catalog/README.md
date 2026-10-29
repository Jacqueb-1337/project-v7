# Project V7 compatibility catalog

**New contributor?** Follow the [step-by-step contribution guide](../CONTRIBUTING.md) for a complete new-game example, common pitfalls, and how to submit a PR.

## Add a game

1. Create `apps/<package-id>.json` with real package/version details and working authorized URLs.
2. Add a game-specific module at `modules/games/<package-id>/<module-id>/module.json` **only if you have game-specific compatibility work to describe**. A module with no operations/payloads does not make a game compatible.
3. Add a profile at `profiles/<package-id>/<version>.json` with a truthful `tested` value.
4. Register each **file path** in `index.json` without removing existing paths.
5. From the repository root, run `python3 scripts/validate_catalog.py` and create a PR against `main`.

## Paths versus IDs

The catalog index lists **relative JSON file paths**:

```json
"modules": ["modules/general/core-compat/module.json"]
```

Profiles list **module IDs** from the `id` field of those manifest files:

```json
"recommendedModules": ["core-compat"]
```

Never use `modules/general/core-compat/module.json` as a value of `recommendedModules`, `dependencies`, or `moduleId`. These fields are resolved by ID. The validator will reject unresolved references, missing index paths, accidental catalog removals, and other structural problems.

## Add reusable compatibility

Create `modules/general/<module-id>/module.json`. Keep game-specific assumptions out of general modules.

## Recommendations

A tested profile defines which modules are preselected for the **exact app and version tested**. Dependencies are resolved automatically. Users may deselect recommended modules unless another selected module requires them.

If there is no matching tested profile, the patcher may suggest compatible general modules that declare `suggestedWhenMatched`. Those are not maintainer-tested recommendations.

## Custom patches

A `.pv7patch` is a ZIP containing `module.json` at its root plus payload files referenced by that module. Custom patches are selected from local storage; Project V7 Patcher does not add third-party patch repositories.

**Note:** a fork's patcher currently fetches its catalog from the official `Jacqueb-1337/project-v7` repository, not automatically from the fork.

## Experimental Unity 4.6 compatibility

`unity-4.6-compat` installs a shared legacy path-rewrite policy but is not a
compatibility guarantee. Verified baseline: CNR 3.0.2 with Unity **4.6.1f1**
ARMv7 only. Native CNR-specific patches and assets remain in `cnr-compat`.
Use **Catalog repository (advanced)** in the manual APK compatibility selector
to test a contributor's fork without claiming that a game is tested.
