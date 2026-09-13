# Oiia
[![English Documentation][badge:doc-en]](../README.md)  
Oiia 是一个面向 IntelliJ IDEA 的 Hearts of Iron IV 模组开发工具包，基于 [Paradox Chronicle](https://plugins.jetbrains.com/plugin/16825-paradox-language-support)（原 Paradox Language Support）开发，为常见的 HOI4 模组文件提供可视化预览和源码跳转能力。
## 功能
- 国策预览：支持 `common/national_focus` 和 `common/continuous_focus`。
- 科技树预览：支持 `common/technologies`。
- GUI 预览：支持 `interface` 目录下的 `.gui` 文件。
- 地图预览：支持地块、省份、国家和战略区域视图。
- HOI4 Mod 项目向导：生成 `descriptor.mod`、可选的启动器 `.mod` 描述文件、README 和 `.gitignore`。
- Shadow 启动器集成：将当前 Paradox Chronicle Mod 加载顺序同步为只读 Shadow 播放集，并从 IntelliJ IDEA 启动 HOI4。
- 在 Paradox Chronicle 能解析的范围内显示本地化名称、图标和详细信息。
- 单击查看详细信息，双击跳转回对应源码文件。
## 环境要求
- IntelliJ IDEA 2026.2 或更高版本。
- 从源码构建需要 Java 21。
- Paradox Chronicle v3.0.1（原 Paradox Language Support）。
- 一个 HOI4 模组项目。建议先在 Paradox Chronicle 中配置好游戏目录和模组目录，这样 Oiia 能正确定位游戏与模组资源。
- 如需使用 Shadow 启动支持，请先安装 Shadow 并刷新一次 Mod 列表，让 Shadow 生成 HOI4 Mod 索引。
## 创建 HOI4 Mod 项目
1. 打开 `File | New | Project...`。
2. 选择 `Game Modding | HOI4 Mod`。
3. 填写 Mod 名称、版本、支持的 HOI4 版本、标签、作者和启动器描述文件选项。
4. 完成向导后会生成项目文件和 HOI4 描述文件。
向导会把创意工坊内容放在生成项目的 `src` 目录下，并将 IntelliJ 项目文件留在该目录之外。
## Shadow 工作流
1. 在 Paradox Chronicle 中配置 HOI4 游戏与 Mod 加载顺序。
2. 在 Shadow 中刷新一次 Mod 列表，让 `mods/index.json` 包含相同的 Mod。
3. 使用 `Tools | Sync Shadow Playset` 创建或更新 Oiia 的只读 Shadow 播放集。
4. 创建 `HOI4 via Shadow` 运行配置，选择 `Shadow.exe`，然后从 IntelliJ IDEA 启动。
运行配置可以在启动后把 HOI4 的 `error.log` 跟随输出到运行控制台。
## 手动安装
1. 从 GitHub Releases 下载最新的 `oiia-*.zip` 文件。
2. 打开 IntelliJ IDEA。
3. 进入 `设置 | 插件 | 从磁盘安装插件...`。
4. 选择下载的 zip 文件，然后重启 IDE。
## 从源码构建
```powershell
.\gradlew.bat buildPlugin
```
生成的插件 zip 位于 `../build/distributions`。
## 许可证
MIT许可证，见 [LICENSE](../LICENSE)。

[badge:doc-en]: https://img.shields.io/badge/English%20Documentation-2f89d7.svg
