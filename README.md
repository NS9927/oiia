# Oiia

[简体中文](doc/README.zh-CN.md)

Oiia is a beta Hearts of Iron IV modding toolkit for IntelliJ IDEA. It builds on
[Paradox Language Support](https://plugins.jetbrains.com/plugin/16825-paradox-language-support)
and adds visual preview tools for common HOI4 modding files.

## Features

- National focus preview for `common/national_focus` and `common/continuous_focus`.
- Technology tree preview for `common/technologies`.
- GUI preview for `.gui` files under `interface`.
- Map preview for provinces, states, countries, and strategic regions.
- Localisation-aware labels and details where Paradox Language Support can resolve them.
- Click details and double-click navigation back to source files.

## Requirements

- IntelliJ IDEA 2026.1.1 or later.
- Java 21 for building from source.
- Paradox Language Support 2.1.9.
- A HOI4 mod project, ideally configured in Paradox Language Support so Oiia can
  locate game and mod resource roots.

## Install The Beta

1. Download the latest `oiia-*.zip` file from GitHub Releases.
2. Open IntelliJ IDEA.
3. Go to `Settings | Plugins | Install Plugin from Disk...`.
4. Select the downloaded zip file and restart the IDE.

This is a beta release. Some preview details may be incomplete or slow on large
mods, especially map and GUI previews.

## Build From Source

```powershell
.\gradlew.bat buildPlugin
```

The plugin zip is written to `build/distributions`.

Useful development commands:

```powershell
.\gradlew.bat runIde
.\gradlew.bat build
```

## Project Status

Oiia is currently focused on preview and navigation workflows for HOI4 modders.
The first beta is intended for manual testing, issue reports, and feedback on
which preview surfaces are most useful.

Please report beta issues at:

https://github.com/NS9927/oiia/issues

## License

MIT. See [LICENSE](LICENSE).
