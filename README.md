# Oiia
[![中文文档][badge:doc-zh]](doc/README.zh-CN.md)  
Oiia is a modding toolkit for **Hearts of Iron IV (HOI4)** built for IntelliJ IDEA.  
It is based on [Paradox Language Support](https://plugins.jetbrains.com/plugin/16825-paradox-language-support) and provides visual previews and navigation features for common HOI4 modding files.
## Features
- National Focus Tree preview for `common/national_focus` and `common/continuous_focus`.
- Technology tree preview for `common/technologies`.
- GUI preview for `.gui` files under the `interface` directory.
- Map preview for provinces, states, countries, and strategic regions.
- Displays localisation-aware names, icons, and metadata when supported by Paradox Language Support.
- Click to view details, double-click to navigate back to source files.
## Requirements
- IntelliJ IDEA 2026.1 or later.
- Java 21 (required for building from source).
- Paradox Language Support 2.1.9.
- A HOI4 mod project.
It is recommended to configure your game and mod directories in Paradox Language Support first, so that Oiia can correctly locate game resources and mod assets.
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
The initial beta version is intended for manual testing and feedback collection, especially regarding which preview features are most useful.
## Issue Tracking
Please report issues here:
https://github.com/NS9927/oiia/issues
## License
MIT License. See [LICENSE](../LICENSE).

[badge:doc-en]: https://img.shields.io/badge/English%20Documentation-2f89d7.svg
[badge:doc-zh]: https://img.shields.io/badge/中文文档-2f89d7.svg