# Oiia
[![中文文档][badge:doc-zh]](doc/README.zh-CN.md)  
Oiia is a modding toolkit for **Hearts of Iron IV (HOI4)** built for IntelliJ IDEA.  
It is based on [Paradox Chronicle](https://plugins.jetbrains.com/plugin/16825-paradox-language-support) (formerly Paradox Language Support) and provides visual previews and navigation features for common HOI4 modding files.
## Features
- National Focus Tree preview for `common/national_focus` and `common/continuous_focus`.
- Technology tree preview for `common/technologies`.
- GUI preview for `.gui` files under the `interface` directory.
- Map preview for provinces, states, countries, and strategic regions.
- HOI4 Mod project wizard that generates `descriptor.mod`, optional launcher `.mod` descriptor, README, and `.gitignore`.
- Shadow launcher integration for syncing the current Paradox Chronicle mod load order into a read-only Shadow playset and launching HOI4 from IntelliJ IDEA.
- Displays localisation-aware names, icons, and metadata when supported by Paradox Chronicle.
- Click to view details, double-click to navigate back to source files.
## Requirements
- IntelliJ IDEA 2026.2 or later.
- Java 21 (required for building from source).
- Paradox Chronicle 3.0.0 (formerly Paradox Language Support).
- A HOI4 mod project.
For Shadow launch support, install and refresh Shadow once so its HOI4 mod index is available.
It is recommended to configure your game and mod directories in Paradox Chronicle first, so that Oiia can correctly locate game resources and mod assets.
## Create a HOI4 Mod Project
1. Open `File | New | Project...`.
2. Select `Game Modding | HOI4 Mod`.
3. Fill in the mod name, version, supported HOI4 version, tags, authors, and launcher descriptor options.
4. Finish the wizard to generate project files and HOI4 descriptor files.
The wizard stores workshop content under the generated `src` directory and keeps IntelliJ project files outside that folder.
## Shadow Workflow
1. Configure the HOI4 game and mod load order in Paradox Chronicle.
2. Refresh Shadow's mod list once so `mods/index.json` contains the same mods.
3. Use `Tools | Sync Shadow Playset` to create or update the read-only Oiia playset in Shadow.
4. Create a `HOI4 via Shadow` run configuration, choose `Shadow.exe`, and run it from IntelliJ IDEA.
The run configuration can follow HOI4 `error.log` in the run console after launch.
## Install
1. Download the latest `oiia-*.zip` from GitHub Releases.
2. Open IntelliJ IDEA.
3. Go to `Settings | Plugins | Install Plugin from Disk...`.
4. Select the downloaded zip file and restart the IDE.
## Build From Source
```powershell
.\gradlew.bat buildPlugin
```
The generated plugin zip will be located in:
```
build/distributions
```
## Project Status
Oiia is currently focused on improving preview and navigation workflows for HOI4 modding.  
Version 1.0.2 adapts Oiia to Paradox Chronicle 3.0.0. Version 1.0.1 previously added project creation and Shadow launch workflows on top of the first stable preview feature set.
## Issue Tracking
Please report issues here:
https://github.com/NS9927/oiia/issues
## License
MIT License. See [LICENSE](./LICENSE).

[badge:doc-en]: https://img.shields.io/badge/English%20Documentation-2f89d7.svg
[badge:doc-zh]: https://img.shields.io/badge/中文文档-2f89d7.svg
