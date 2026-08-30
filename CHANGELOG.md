# Oiia Changelog

## [Unreleased]

### Fixed

- Fixes a race in the sprite resolver where rebuilding the icon cache could corrupt concurrently memoised sprite lookups; caches are now swapped atomically.
- Sprite and localisation caches now detect edits to `.gfx` / `.yml` files (short TTL fingerprinting) instead of staying stale until resource roots or preferences change.
- Focus and technology previews parse on a background thread (no longer blocking the UI thread) and refresh automatically while the file is being edited.
- The focus-tree text fallback parser now honours Paradox booleans (`default = yes` previously evaluated to false) and is built on the shared token parser.
- GUI preview no longer duplicates the shared PLS localisation resolution; every module resolves localisation through `core/ParadoxLocalisationResolver`.

### Changed

- Consolidates fallback language order and localisation scoring weights into `ParadoxLocalisationPreference` (previously four drifting copies across preview modules).
- Moves the shared text fallback parser into `core/ParadoxTextParser` (used by the technology preview) and extracts the pure `.gfx` text parsing into `ParadoxGfxParser`.
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
