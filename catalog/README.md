# Project V7 compatibility catalog

## Add a game
Create `modules/games/<package-id>/<module-id>/module.json`, then add or update `profiles/<package-id>/<version>.json` if you have a tested recommended set.

A tested profile controls the modules preselected in the patcher for that exact app/version. Compatibility alone does not imply recommendation.

## Add reusable compatibility
Create `modules/general/<module-id>/module.json`. Keep game-specific assumptions out of general modules.

## Recommendations
Use `recommendedModules` in a tested profile for the exact configuration that has been rebuilt and tested. Dependencies are resolved automatically. Users may deselect recommended modules unless another selected module requires them.

If there is no matching tested profile, the patcher may suggest compatible general modules that declare `suggestedWhenMatched`, but those are not maintainer-tested recommendations for that game.

## Custom patches
A `.pv7patch` is a ZIP containing `module.json` at its root plus any payload files referenced by that module. Custom patches are selected from local storage; Project V7 Patcher does not add third-party patch repositories.
