# Oiia Changelog 更新日志

## 1.1.6 - 2026-09-13

### Added 新增

- 新增 GitHub Actions 工作流：推送 `v*` tag 时自动构建插件、运行测试并创建对应的 GitHub Release 挂载插件 zip（CI 参考 Shadow 的模式）；push/PR 到 main 也会执行测试与构建，tag 会先校验与 `gradle.properties` 的版本一致。
- Added a GitHub Actions workflow: pushing a `v*` tag builds the plugin, runs the tests and attaches the plugin zip to a matching GitHub Release (CI modelled on Shadow's); pushes and PRs to main run tests and builds too, and tags are verified against the `gradle.properties` version first.

### Changed 变更

- 适配 Shadow 的新目录结构：播放集同步的工作区现在优先解析 `%APPDATA%\Posdaca\Shadow\<Game>`，旧布局（`Posdaca\<Game>` 与 `Hoi4Workspace`）保留为回退。
- Adapts to Shadow's new directory layout: playset sync resolves `%APPDATA%\Posdaca\Shadow\<Game>` first, keeping the older layouts (`Posdaca\<Game>` and `Hoi4Workspace`) as fallbacks.
- 地图预览的 DLC 条件默认不勾选：初始渲染与勾选框状态一致（未勾选 = 基础归属），勾选后即时应用对应变更。
- Map preview DLC conditions now start unchecked, keeping the initial render consistent with the checkbox state (unchecked = base ownership); checking applies the changes immediately.

### Fixed 修复

- 修复悬停提示对 DLC 条件归属国家显示「未知」的问题：`countryByTag` 现在覆盖州历史条件变更中引用的 tag（如 HBC/SIC），时间线激活时悬停状态栏、详情与高亮框均跟随解析后的归属。
- Fixed hover hints showing "未知" (unknown) for DLC-conditioned owner tags: `countryByTag` now covers tags referenced by state-history conditional changes (e.g. HBC/SIC), and hover status, details and highlights follow the resolved ownership while the timeline is active.
- 修复切换时间点/勾选 DLC 后国家边界与高亮框不更新的问题：归属变化会重建国家边界区块、平滑段、像素键数组与国家范围。
- Fixed country borders and hover highlights not updating after timeline/DLC changes: ownership changes now rebuild the country border chunks, smooth segments, per-pixel keys and country bounds.
- 修复 CI 中 `gradlew` 缺少可执行权限的问题。
- Fixed the missing executable bit on `gradlew` that broke CI.

## 1.1.5 - 2026-09-09

### Changed 变更

- Shadow 播放集同步现在读写 `%APPDATA%\Posdaca\Hearts of Iron IV`（Shadow 当时的 HOI4 工作区）；仅在缺少该索引时回退旧的 `Hoi4Workspace` 目录。
- Shadow playset sync now reads and writes `%APPDATA%\Posdaca\Hearts of Iron IV` (Shadow's HOI4 workspace at the time); the old `Hoi4Workspace` folder is used only when that newer mod index is missing.
- 适配 Paradox Chronicle 3.0.2（settings 类型移至 `icu.windea.pls.base.settings`、`ParadoxDefinitionElement` 移至 `icu.windea.pls.lang.psi`、`searchIcon` 更名 `searchImage`）。
- Depends on Paradox Chronicle 3.0.2 (settings types moved to `icu.windea.pls.base.settings`, `ParadoxDefinitionElement` to `icu.windea.pls.lang.psi`, and `searchIcon` to `searchImage`).

## 1.1.4 - 2026-09-07

国策预览现在遵循游戏的 `prerequisite` 分组与连线样式，拖拽国策也不再破坏脚本坐标。

National focus preview now follows the game's `prerequisite` grouping and joint style, and dragging a focus no longer corrupts its script coordinates.

### Fixed 修复

- 国策预览保留 `prerequisite` 块分组：同一块内多个 `focus` 为 OR（虚线连线），不同块为 AND（实线连线）；悬停提示同步显示分组，当前树中任一前置块无可见成员时隐藏该国策（其子节点一并隐藏避免悬空）。
- National focus preview keeps `prerequisite` block grouping: several `focus` entries in one block are OR (dashed joint), separate blocks are AND (solid joint). Hover text shows the same grouping, and a focus is hidden when any prerequisite block has no member in the current tree (children of hidden focuses are dropped so they do not float).
- 修复拖拽国策时坐标拼接的问题（`x = 5` 变成 `x = 55`）：Chronicle 把整数留在属性值范围外时也能正确回写。
- Dragging a national focus no longer concatenates coordinates (`x = 5` becoming `x = 55`) when Chronicle leaves the integer outside the property value range.

## 1.1.3 - 2026-08-31

地图时间线现在支持 `has_dlc` 门控的归属，与原版用 DLC 控制开局设定的方式一致（如 No Compromise, No Surrender 的东亚重做）。

The map timeline now understands `has_dlc`-gated ownership, matching how vanilla ships DLC-dependent start setups (e.g. the East Asia rework in No Compromise, No Surrender).

### Added 新增

- 地图预览在时间线旁新增 DLC 条件选择器：列出州历史中引用的 `has_dlc` 条件，按所选时间点与 DLC 组合重新计算归属（对应游戏的条件历史脚本 `IF = { limit = { has_dlc = ... } transfer_state_to = ... }`）。
- Map preview: a DLC selector next to the timeline lists the `has_dlc` conditions referenced by the loaded state histories; owner/controller fills are re-evaluated for the selected timeline point + DLC combination, matching the game's conditional state-history script (`IF = { limit = { has_dlc = ... } transfer_state_to = ... }`).

## 1.1.2 - 2026-08-31

新增 GFX 工具窗口，参照 hoi4modutilities 对地图预览做了一轮保真度与体验改进，并修复预览大地图时的 IDE 内存卡死。

New GFX tool window, a round of map-preview fidelity and UX work modelled on the hoi4modutilities reference, and a memory overhaul that fixes an IDE freeze when previewing large maps.

### Added 新增

- 新增 GFX 工具窗口：打开 `.gfx` 文件即以网格展示其中声明的全部精灵（按解析后的纹理渲染），含名称/纹理/帧数提示与双击跳转定义。
- New GFX tool window: opening a `.gfx` file shows a grid of every sprite it declares, rendered from the resolved textures, with name/texture/frame tooltips and double-click source navigation.
- 地图预览新增 `地形`、`占领控制` 配色，以及 `人力`、`胜利点`、`资源`（绿→黄→红热力）与 `州类别`、`省份类型`、`大陆` 六种配色。
- Map preview color sets `Terrain` and `Controller` (game-start controller falls back to the owner) plus `Manpower`, `Victory Points`, `Resources` (green→yellow→red heat ramps), `State Category`, `Province Type` and `Continent`.
- 地图预览新增时间线选择器：`common/bookmarks` 的剧本日期成为时间点，州历史中带日期的归属/控制变更按所选日期生效并重着色。
- Map preview timeline selector: game bookmarks from `common/bookmarks` become timeline points, and dated owner/controller changes inside state histories are applied, recolouring the fills as of the selected date.
- 地图预览新增数据体检面板：列出数据完整性警告（provinces.bmp 颜色缺失于 definition.csv、重复行、州引用缺失省份、省份多归属或无归属、战区引用缺失省份），地图红色标示并可点击定位。
- Map preview issue panel: data-integrity warnings (provinces.bmp colours missing from definition.csv, duplicate definition rows, states referencing missing provinces, provinces assigned to multiple or no states, strategic regions referencing missing provinces) are listed, tinted red on the map and can be clicked to locate the region.
- 不可通行州以红色边界标示（开启边框时可见），非军事区省份以斜纹覆盖；州详情新增控制国、不可通行与非军事区行。
- Impassable states are marked with red boundaries (visible when borders are shown) and demilitarized-zone provinces get a diagonal hatch; state details include controller, impassable and demilitarized-zone rows.
- 工具栏新增定位输入框：按 ID 或（本地化）名称跳转到州/省份/国家/战区并居中高亮。
- The toolbar gained a locate field: jump to a state / province / country / region by id or (localized) name, highlighting it and centering the viewport.
- 新增「导出」：以原生 1:1 分辨率渲染整张地图（填色、边界与标签）并通过保存对话框输出 PNG。
- An `Export` action renders the whole map at native 1:1 scale (fill, borders and labels) into a PNG via a save dialog.
- 地图标签锚定到区域像素质心（跨地图接缝做环绕修正），附第二行淡色区域 ID，使用屏幕固定字号（不随缩放），按渲染颜色自动选择黑/白墨色，并在低倍缩放时隐藏过小区域 - 对齐 hoi4modutilities 的标签模型。
- Map labels anchor to each region's mass-weighted pixel centroid (with seam-aware wrap correction), draw a second dim line with the region id, use a screen-fixed font, pick black/white ink by the rendered colour, and hide labels of regions too small on screen - matching the label model of the hoi4modutilities reference.
- 为此前无测试的核心逻辑补充单测锚点：GUI 布局引擎、地图纯函数像素数学（边界、分区均匀性、质心合并、标签墨色）、sprite 解析器路径/指纹逻辑、前缀图标查找与 HOI4 资源根规划。
- Unit-test anchors for previously untested core logic: the GUI layout engine, the map preview's pure pixel math, the sprite resolver's path/stamp seams, prefix icon lookup, and HOI4 resource-root planning.

### Changed 变更

- 地图工具栏的颜色与视图模式解耦：颜色选择器决定填色（省份/州/国家/战区/地形/占领控制），视图模式决定边界、标签、悬停与详情，任意组合可用。
- The map toolbar's color and view modes are decoupled: the color selector picks the fill while the view-mode selector drives borders, labels, hover selection and details.
- GUI 预览的布局引擎（尺寸、边界、锚点、问题检测）下沉为 `GuiPreviewService.layoutRoot` 纯函数，面板只消费布局结果。
- The GUI preview's layout engine moved into `GuiPreviewService.layoutRoot`, a pure function; panels only consume the laid-out nodes.
- 地图边界检测与分区均匀性抽为纯函数 `MapPixels`，由 service 与 panel 共享。
- Map boundary detection and render-zone uniformity are extracted into the pure `MapPixels` object.
- HOI4 资源根实现移至 `core/files`，`ResourceFiles` 成为唯一资源根门面。
- The HOI4 resource-root implementation now lives under `core/files`, leaving `ResourceFiles` as the single resource-root facade.
- Sprite 与本地化门面对所有 PLS/PSI 访问自带 read action 保护，后台调用不会再触发线程断言。
- Sprite and localisation facades now self-protect all PLS/PSI access with read actions.

### Fixed 修复

- 修复预览大地图时的 IDE 卡死（`OutOfMemoryError` 风暴）：平滑边界段改为按视图模式懒构建，不可通行边界不再分配整图键数组，GFX 预览纹理解码增加 192MB 预算。
- Fixes an IDE freeze (`OutOfMemoryError` storm) when loading large maps: smooth border segments are built lazily per view mode, the impassability pass no longer allocates a full-map key array, and the GFX preview stops decoding textures after a 192 MB budget.
- 修复科技文件 `enable_equipments = no` 被判定为 true 的问题。
- `enable_equipments = no` in technology files was evaluated as true.

## 1.1.1 - 2026-08-30

### Fixed 修复

- 科技树文件夹改为按游戏页签顺序横向并排（右侧为下一个文件夹），文件夹内的树在各自列中纵向堆叠，不再纵向罗列。
- Technology folders are now laid out side by side (the next folder to the right of the previous one) in the game's tab order, instead of stacked vertically; each folder's trees stack vertically inside its own column.

## 1.1.0 - 2026-08-30

全部脚本结构解析重建于 Paradox Chronicle 的 PSI 之上，科技树预览对齐游戏树布局。

Rebuilds all script parsing on Paradox Chronicle's PSI and aligns the technology tree preview with the game's tree layout.

### Added 新增

- 科技树预览对齐游戏树结构：文件夹内科技按 `leads_to_tech` 拆分为连通树（对应 `countrytechtreeview.gui` 的每树 gridbox），树的格距、主轴（`format = up/down/left/right`）与格子尺寸读自对应 gridbox。
- Technology previews now mirror the game's tree structure: technologies in a folder are split into connected trees by `leads_to_tech`, and each tree's grid spacing, axis and slot size are read from the matching gridbox in `countrytechtreeview.gui` / `countrydoctrinetreeview.gui` when present.

### Fixed 修复

- 修复横排科技树渲染成竖排的问题：gridbox `format = "LEFT"`（原文件带引号）此前未被识别。同时支持嵌套容器中的 gridbox 查找、起始科技名不一致时按成员匹配、树按 gridbox 原点排序。
- Fixed horizontal trees rendering vertically: gridbox `format = "LEFT"` (quoted in vanilla files) was not recognised. Gridboxes are also found in nested containers, trees match their gridbox by any member id when the start-tech name differs, and trees are ordered by their gridbox origin.
- 修复后台解析科技图标时的崩溃（"Read access is allowed from inside read-action only"）：sprite 解析器的 `.gfx` PSI 扫描现在完整运行在 per-file read action 内。
- Fixes a crash when resolving technology icons on a background thread: the sprite resolver's `.gfx` PSI scan now runs fully inside a per-file read action.
- 修复拖拽节点时的崩溃：国策/GUI 拖拽回写的 PSI 查找现在包在 read action 内（新版平台的 EDT 不再隐式持有读权限）。
- Fixes a crash when dragging nodes: the focus / GUI drag write-back now wraps its PSI lookup in a read action.
- 修复 sprite 解析器重建图标缓存时并发读取被破坏的竞态；缓存改为原子交换。
- Fixes a race in the sprite resolver where rebuilding the icon cache could corrupt concurrent lookups; caches are now swapped atomically.
- sprite 与本地化缓存现在能感知 `.gfx` / `.yml` 文件编辑（短 TTL 指纹），不再等到资源根或偏好变化才失效。
- Sprite and localisation caches now detect edits to `.gfx` / `.yml` files (short TTL fingerprinting).
- 国策与科技预览改为后台线程解析（不再阻塞 UI 线程），编辑文件时自动刷新。
- Focus and technology previews parse on a background thread and refresh automatically while the file is being edited.
- 修复科技 `force_use_small_tech_layout = yes` 被判定为 false 的问题；现在遵循 Paradox 布尔值。
- Technology `force_use_small_tech_layout = yes` was evaluated as false; Paradox booleans are now honoured.
- 科技图标改走游戏图标链（`GFX_<techid>_medium`，回退通用 `GFX_technology_medium`），不再按名字猜测。
- Technology icons resolve through the game's icon chain (`GFX_<techid>_medium`, falling back to the generic `GFX_technology_medium`).
- GUI 预览不再复制共享的 PLS 本地化解析；所有模块统一经 `core/ParadoxLocalisationResolver` 解析。
- GUI preview no longer duplicates the shared PLS localisation resolution; every module resolves localisation through `core/ParadoxLocalisationResolver`.

### Changed 变更

- 全部 Paradox 脚本结构解析改走 PLS（Chronicle）PSI：移除国策/科技/GUI/地图州/战区/国家文件的手写文本解析器与正则 `.gfx` 精灵扫描；`map/definition.csv`（CSV）与本地化 yml 合并按设计保留自研。
- All Paradox-script structure parsing now goes through PLS (Chronicle) PSI; `map/definition.csv` (CSV) and localisation yml merging remain custom by design.
- 依赖 Paradox Chronicle 3.0.2（settings 类型移至 `icu.windea.pls.base.settings`、`ParadoxDefinitionElement` 移至 `icu.windea.pls.lang.psi`、`searchIcon` 更名 `searchImage`）。
- Depends on Paradox Chronicle 3.0.2.
- 回退语言顺序与本地化打分权重收敛到 `ParadoxLocalisationPreference`（此前四个预览模块各有一份漂移的副本），纯文本 `.gfx` 解析抽出为 `ParadoxGfxParser`。
- Consolidates fallback language order and localisation scoring weights into `ParadoxLocalisationPreference` and extracts the pure `.gfx` text parsing into `ParadoxGfxParser`.
- 移除未使用的资源文件 API 与废弃的游戏 GUI 模板加载器。
- Removes unused resource-file APIs and the unused game GUI template loader.
- `HOI4 via Shadow` 运行配置改用 Shadow 的 `PDXGameLauncher hoi4 -playset <id>` 命令行入口（替换 `--shadow-command hoi4.launch`）。
- Updates the `HOI4 via Shadow` run configuration to Shadow's `PDXGameLauncher hoi4 -playset <id>` CLI entry.

## 1.0.2 - 2026-07-15

适配 Oiia 到 Paradox Chronicle 3.0.0。

Adapts Oiia to Paradox Chronicle 3.0.0.

### Changed 变更

- 依赖 Paradox Chronicle 3.0.0（原 Paradox Language Support；插件 id 仍为 `icu.windea.pls`）。
- Depends on Paradox Chronicle 3.0.0 (formerly Paradox Language Support; plugin id remains `icu.windea.pls`).
- 适配更名的 `CwtLocaleConfig` API（`id` -> `name`）。
- Updates locale preference handling for the renamed `CwtLocaleConfig` API (`id` -> `name`).
- 更新插件更名与版本要求的英文和简体中文文档。
- Updates English and Simplified Chinese documentation for the plugin rename and version requirement.

## 1.0.1 - 2026-06-17

新增项目创建与 Shadow 启动器工作流。

Adds project creation and Shadow launcher workflows.

### Added 新增

- `New Project | Game Modding | HOI4 Mod` 下的 HOI4 模组项目向导：生成 `descriptor.mod`、可选启动器 `.mod` 描述、README 与 `.gitignore`。
- HOI4 Mod project wizard under `New Project | Game Modding | HOI4 Mod`, generating `descriptor.mod`, optional launcher `.mod` descriptor, README, and `.gitignore`.
- `Tools | Sync Shadow Playset` 下的 Shadow 播放集同步：按 remote file ID 或规范化内容路径将 PLS 的 HOI4 模组加载顺序匹配到 Shadow 的模组索引。
- Shadow playset sync action under `Tools | Sync Shadow Playset`, matching PLS HOI4 mod load order against Shadow's mod index by remote file ID or normalized content path.
- `HOI4 via Shadow` 运行配置：通过同步的播放集启动 HOI4。
- `HOI4 via Shadow` run configuration that launches HOI4 through Shadow using the synced playset.
- 启动后可选在控制台流式输出 HOI4 的 `error.log`。
- Optional console streaming of HOI4 `error.log` after launch.
- 项目向导与 Shadow 工具的英文和简体中文消息。
- English and Simplified Chinese messages for the new project wizard and Shadow tooling.

### Tests 测试

- 项目模板生成、Shadow 播放集匹配/写入、Shadow 日志处理与 Shadow 运行配置默认值的单元测试。
- Unit tests for project template generation, Shadow playset matching/writing, Shadow log handling, and Shadow run configuration defaults.

## 1.0.0 - 2026-06-11

首个正式版本。

First stable release.

### Added 新增

- 国策树预览：图标加载、前置连线、互斥连线、悬停详情与源码跳转。
- National focus tree preview with icon loading, prerequisite links, mutually exclusive links, hover details, and source navigation.
- 科技树预览：文件夹分组、图标加载、路径连线、悬停详情与源码跳转。
- Technology tree preview with folder grouping, icon loading, path links, hover details, and source navigation.
- HOI4 `.gui` 文件的 GUI 预览：精灵、文本、布局与元素详情。
- GUI preview for HOI4 `.gui` files with sprite, text, layout, and element details.
- 地图预览：省份/州/国家/战略区域着色，可选颜色与边界模式。
- Map preview for provinces, states, countries, and strategic regions with selectable color and border modes.
- 与 Paradox Language Support 集成：HOI4 项目推断、资源根、本地化与精灵解析。
- Integration with Paradox Language Support for HOI4 project inference, resource roots, localisation, and sprite resolution.
- 英文与简体中文 UI 消息。
- English and Simplified Chinese UI messages.

### Known Limitations 已知限制

- 大地图与 GUI 预览加载可能较慢。
- Large map and GUI previews can take time to load.
- 部分 HOI4 GUI effect 与精灵场景为近似渲染。
- Some HOI4 GUI effect and sprite cases may render approximately rather than exactly.
- 目前通过 GitHub Releases 手动下载插件 zip 安装。
- Installation currently uses a manually downloaded plugin zip from GitHub Releases.
