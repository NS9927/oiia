# Oiia Changelog

## [1.1.1] - 2026-08-30

### Fixed

- Technology folders are now laid out side by side (the next folder to the right of the previous one) in the game's tab order, instead of stacked vertically; each folder's trees stack vertically inside its own column.

## [1.1.0] - 2026-08-30

Rebuilds all script parsing on Paradox Chronicle's PSI and aligns the technology tree preview with the game's tree layout.

### Added

- Technology previews now mirror the game's tree structure: technologies in a folder are split into connected trees by `leads_to_tech` (matching the per-tree gridboxes in `countrytechtreeview.gui`), and each tree's grid spacing, axis (`format = up/down/left/right`) and slot size are read from the matching gridbox in `countrytechtreeview.gui` / `countrydoctrinetreeview.gui` when present.

### Fixed

- Technology trees now grow along the game's main axis: gridbox `format = "LEFT"` (quoted, as written in vanilla `countrytechtreeview.gui`) was not recognised, so horizontal trees rendered vertically. Gridboxes are also found in nested containers, trees match their gridbox by any member id when the start-tech name differs, and trees are ordered by their gridbox origin within the folder page.
- Fixes a crash when resolving technology icons on a background thread ("Read access is allowed from inside read-action only"): the sprite resolver's `.gfx` PSI scan now runs fully inside a per-file read action.
- Fixes a crash when dragging nodes ("Read access is allowed from inside read-action only"): the focus / GUI drag write-back now wraps its PSI lookup in a read action, as EDT no longer has implicit read access on current IntelliJ Platform builds.
- Fixes a race in the sprite resolver where rebuilding the icon cache could corrupt concurrently memoised sprite lookups; caches are now swapped atomically.
- Sprite and localisation caches now detect edits to `.gfx` / `.yml` files (short TTL fingerprinting) instead of staying stale until resource roots or preferences change.
- Focus and technology previews parse on a background thread (no longer blocking the UI thread) and refresh automatically while the file is being edited.
- Technology `force_use_small_tech_layout = yes` was evaluated as false; Paradox booleans are now honoured.
- Technology icons resolve through the game's icon chain (`GFX_<techid>_medium`, falling back to the generic `GFX_technology_medium`) instead of ad-hoc name guesses.
- GUI preview no longer duplicates the shared PLS localisation resolution; every module resolves localisation through `core/ParadoxLocalisationResolver`.

### Changed

- All Paradox-script structure parsing now goes through PLS (Chronicle) PSI: the hand-written text fallback parsers for focus / technology / GUI / map state / strategic region / country files and the regex `.gfx` sprite scan were removed. `map/definition.csv` (CSV) and localisation yml merging remain custom by design.
- Depends on Paradox Chronicle 3.0.1 (adapting to its updated APIs).
- Consolidates fallback language order and localisation scoring weights into `ParadoxLocalisationPreference` (previously four drifting copies across preview modules) and extracts the pure `.gfx` text parsing into `ParadoxGfxParser`.
- Removes unused resource-file APIs and the unused game GUI template loader.
- Updates the `HOI4 via Shadow` run configuration to Shadow's `PDXGameLauncher hoi4 -playset <id>` CLI entry (replacing `--shadow-command hoi4.launch`).

## [1.0.2] - 2026-07-15

Adapts Oiia to Paradox Chronicle 3.0.0.

### Changed

- Depends on Paradox Chronicle 3.0.0 (formerly Paradox Language Support; plugin id remains `icu.windea.pls`).
- Updates locale preference handling for the renamed `CwtLocaleConfig` API (`id` -> `name`).
- Updates English and Simplified Chinese documentation for the plugin rename and version requirement.

## [1.0.1] - 2026-06-17

Adds project creation and Shadow launcher workflows.

### Added

- HOI4 Mod project wizard under `New Project | Game Modding | HOI4 Mod`, generating `descriptor.mod`, optional launcher `.mod` descriptor, README, and `.gitignore`.
- Shadow playset sync action under `Tools | Sync Shadow Playset`, matching PLS HOI4 mod load order against Shadow's mod index by remote file ID or normalized content path.
- `HOI4 via Shadow` run configuration that launches HOI4 through Shadow using the synced playset.
- Optional console streaming of HOI4 `error.log` after launch.
- English and Simplified Chinese messages for the new project wizard and Shadow tooling.

### Tests

- Unit tests for project template generation, Shadow playset matching/writing, Shadow log handling, and Shadow run configuration defaults.

## [1.0.0] - 2026-06-11

First stable release.

### Added

- National focus tree preview with icon loading, prerequisite links, mutually exclusive links, hover details, and source navigation.
- Technology tree preview with folder grouping, icon loading, path links, hover details, and source navigation.
- GUI preview for HOI4 `.gui` files with sprite, text, layout, and element details.
- Map preview for provinces, states, countries, and strategic regions with selectable color and border modes.
- Integration with Paradox Language Support for HOI4 project inference, resource roots, localisation, and sprite resolution.
- English and Simplified Chinese UI messages.

### Known Limitations

- Large map and GUI previews can take time to load.
- Some HOI4 GUI effect and sprite cases may render approximately rather than exactly.
- Installation currently uses a manually downloaded plugin zip from GitHub Releases.
