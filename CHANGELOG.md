# Changelog

## 1.1.6 - 2026-09-13

### Added

- GitHub Actions workflow: pushing a `v*` tag builds the plugin, runs the tests and attaches the plugin zip to a matching GitHub Release (CI modelled on Shadow's); pushes and PRs to main run tests and builds too, and tags are verified against the `gradle.properties` version first.

### Changed

- Adapts to Shadow's new directory layout: playset sync resolves `%APPDATA%\Posdaca\Shadow\<Game>` first, keeping the older layouts (`Posdaca\<Game>` and `Hoi4Workspace`) as fallbacks.
- Map preview DLC conditions now start unchecked, keeping the initial render consistent with the checkbox state (unchecked = base ownership); checking applies the changes immediately.

### Fixed

- Fixed hover hints showing "unknown" for DLC-conditioned owner tags: `countryByTag` now covers tags referenced by state-history conditional changes (e.g. HBC/SIC), and hover status, details and highlights follow the resolved ownership while the timeline is active.
- Fixed country borders and hover highlights not updating after timeline/DLC changes: ownership changes now rebuild the country border chunks, smooth segments, per-pixel keys and country bounds.
- Fixed the missing executable bit on `gradlew` that broke CI.

## 1.1.5 - 2026-09-09

### Changed

- Shadow playset sync now reads and writes `%APPDATA%\Posdaca\Hearts of Iron IV` (Shadow's HOI4 workspace at the time); the old `Hoi4Workspace` folder is used only when that newer mod index is missing.
- Depends on Paradox Chronicle 3.0.2 (settings types moved to `icu.windea.pls.base.settings`, `ParadoxDefinitionElement` to `icu.windea.pls.lang.psi`, and `searchIcon` to `searchImage`).

## 1.1.4 - 2026-09-07

National focus preview now follows the game's `prerequisite` grouping and joint style, and dragging a focus no longer corrupts its script coordinates.

### Fixed

- National focus preview keeps `prerequisite` block grouping: several `focus` entries in one block are OR (dashed joint), separate blocks are AND (solid joint). Hover text shows the same grouping, and a focus is hidden when any prerequisite block has no member in the current tree (children of hidden focuses are dropped so they do not float).
- Dragging a national focus no longer concatenates coordinates (`x = 5` becoming `x = 55`) when Chronicle leaves the integer outside the property value range.

## 1.1.3 - 2026-08-31

The map timeline now understands `has_dlc`-gated ownership, matching how vanilla ships DLC-dependent start setups (e.g. the East Asia rework in No Compromise, No Surrender).

### Added

- Map preview: a DLC selector next to the timeline lists the `has_dlc` conditions referenced by the loaded state histories; owner/controller fills are re-evaluated for the selected timeline point + DLC combination, matching the game's conditional state-history script (`IF = { limit = { has_dlc = ... } transfer_state_to = ... }`).

## 1.1.2 - 2026-08-31

New GFX tool window, a round of map-preview fidelity and UX work modelled on the hoi4modutilities reference, and a memory overhaul that fixes an IDE freeze when previewing large maps.

### Added

- New GFX tool window: opening a `.gfx` file shows a grid of every sprite it declares, rendered from the resolved textures, with name/texture/frame tooltips and double-click source navigation.
- Map preview color sets `Terrain` and `Controller` (game-start controller falls back to the owner) plus `Manpower`, `Victory Points`, `Resources` (green→yellow→red heat ramps), `State Category`, `Province Type` and `Continent`.
- Map preview timeline selector: game bookmarks from `common/bookmarks` become timeline points, and dated owner/controller changes inside state histories are applied, recolouring the fills as of the selected date.
- Map preview issue panel: data-integrity warnings (provinces.bmp colours missing from definition.csv, duplicate definition rows, states referencing missing provinces, provinces assigned to multiple or no states, strategic regions referencing missing provinces) are listed, tinted red on the map and can be clicked to locate the region.
- Impassable states are marked with red boundaries (visible when borders are shown) and demilitarized-zone provinces get a diagonal hatch; state details include controller, impassable and demilitarized-zone rows.
- The toolbar gained a locate field: jump to a state / province / country / region by id or (localized) name, highlighting it and centering the viewport.
- An `Export` action renders the whole map at native 1:1 scale (fill, borders and labels) into a PNG via a save dialog.
- Map labels anchor to each region's mass-weighted pixel centroid (with seam-aware wrap correction), draw a second dim line with the region id, use a screen-fixed font, pick black/white ink by the rendered colour, and hide labels of regions too small on screen - matching the label model of the hoi4modutilities reference.
- Unit-test anchors for previously untested core logic: the GUI layout engine, the map preview's pure pixel math, the sprite resolver's path/stamp seams, prefix icon lookup, and HOI4 resource-root planning.

### Changed

- The map toolbar's color and view modes are decoupled: the color selector picks the fill while the view-mode selector drives borders, labels, hover selection and details.
- The GUI preview's layout engine moved into `GuiPreviewService.layoutRoot`, a pure function; panels only consume the laid-out nodes.
- Map boundary detection and render-zone uniformity are extracted into the pure `MapPixels` object.
- The HOI4 resource-root implementation now lives under `core/files`, leaving `ResourceFiles` as the single resource-root facade.
- Sprite and localisation facades now self-protect all PLS/PSI access with read actions.

### Fixed

- Fixes an IDE freeze (`OutOfMemoryError` storm) when loading large maps: smooth border segments are built lazily per view mode, the impassability pass no longer allocates a full-map key array, and the GFX preview stops decoding textures after a 192 MB budget.
- `enable_equipments = no` in technology files was evaluated as true.

## 1.1.1 - 2026-08-30

### Fixed

- Technology folders are now laid out side by side (the next folder to the right of the previous one) in the game's tab order, instead of stacked vertically; each folder's trees stack vertically inside its own column.

## 1.1.0 - 2026-08-30

Rebuilds all script parsing on Paradox Chronicle's PSI and aligns the technology tree preview with the game's tree layout.

### Added

- Technology previews now mirror the game's tree structure: technologies in a folder are split into connected trees by `leads_to_tech`, and each tree's grid spacing, axis and slot size are read from the matching gridbox in `countrytechtreeview.gui` / `countrydoctrinetreeview.gui` when present.

### Fixed

- Fixed horizontal trees rendering vertically: gridbox `format = "LEFT"` (quoted in vanilla files) was not recognised. Gridboxes are also found in nested containers, trees match their gridbox by any member id when the start-tech name differs, and trees are ordered by their gridbox origin.
- Fixes a crash when resolving technology icons on a background thread: the sprite resolver's `.gfx` PSI scan now runs fully inside a per-file read action.
- Fixes a crash when dragging nodes: the focus / GUI drag write-back now wraps its PSI lookup in a read action.
- Fixes a race in the sprite resolver where rebuilding the icon cache could corrupt concurrent lookups; caches are now swapped atomically.
- Sprite and localisation caches now detect edits to `.gfx` / `.yml` files (short TTL fingerprinting).
- Focus and technology previews parse on a background thread and refresh automatically while the file is being edited.
- Technology `force_use_small_tech_layout = yes` was evaluated as false; Paradox booleans are now honoured.
- Technology icons resolve through the game's icon chain (`GFX_<techid>_medium`, falling back to the generic `GFX_technology_medium`).
- GUI preview no longer duplicates the shared PLS localisation resolution; every module resolves localisation through `core/ParadoxLocalisationResolver`.

### Changed

- All Paradox-script structure parsing now goes through PLS (Chronicle) PSI; `map/definition.csv` (CSV) and localisation yml merging remain custom by design.
- Depends on Paradox Chronicle 3.0.2.
- Consolidates fallback language order and localisation scoring weights into `ParadoxLocalisationPreference` and extracts the pure `.gfx` text parsing into `ParadoxGfxParser`.
- Removes unused resource-file APIs and the unused game GUI template loader.
- Updates the `HOI4 via Shadow` run configuration to Shadow's `PDXGameLauncher hoi4 -playset <id>` CLI entry.

## 1.0.2 - 2026-07-15

Adapts Oiia to Paradox Chronicle 3.0.0.

### Changed

- Depends on Paradox Chronicle 3.0.0 (formerly Paradox Language Support; plugin id remains `icu.windea.pls`).
- Updates locale preference handling for the renamed `CwtLocaleConfig` API (`id` -> `name`).
- Updates English and Simplified Chinese documentation for the plugin rename and version requirement.

## 1.0.1 - 2026-06-17

Adds project creation and Shadow launcher workflows.

### Added

- HOI4 Mod project wizard under `New Project | Game Modding | HOI4 Mod`, generating `descriptor.mod`, optional launcher `.mod` descriptor, README, and `.gitignore`.
- Shadow playset sync action under `Tools | Sync Shadow Playset`, matching PLS HOI4 mod load order against Shadow's mod index by remote file ID or normalized content path.
- `HOI4 via Shadow` run configuration that launches HOI4 through Shadow using the synced playset.
- Optional console streaming of HOI4 `error.log` after launch.
- English and Simplified Chinese messages for the new project wizard and Shadow tooling.

### Tests

- Unit tests for project template generation, Shadow playset matching/writing, Shadow log handling, and Shadow run configuration defaults.

## 1.0.0 - 2026-06-11

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
