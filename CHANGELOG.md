# Oiia Changelog

## [1.0.1] - 2026-06-17

Adds project creation and Shadow launcher workflows.

### Added

- HOI4 Mod project wizard under `New Project | Game Modding | HOI4 Mod`, generating `descriptor.mod`, optional launcher `.mod` descriptor, README, and `.gitignore`.
- Shadow playset sync action under `Tools | Sync Shadow Playset`, matching PLS HOI4 mod load order against Shadow's mod index by remote file ID or normalized content path.
- `HOI4 via Shadow` run configuration that launches HOI4 through Shadow using the synced playset.
- Optional HOI4 `error.log` console tailing after Shadow launch.
- English and Simplified Chinese UI messages for project creation and Shadow workflows.
- Unit tests for project template generation, Shadow playset matching/writing, Shadow log handling, and Shadow run configuration defaults.

### Changed

- Declared IntelliJ Java plugin dependency for project/module creation support.
- Added Gson dependency for reading and writing Shadow workspace JSON.

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
