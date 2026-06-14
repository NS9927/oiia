# Oiia
[![English Documentation][badge:doc-en]](../README.md)  
Oiia 是一个面向 IntelliJ IDEA 的 Hearts of Iron IV 模组开发工具包，基于 [Paradox Language Support](https://plugins.jetbrains.com/plugin/16825-paradox-language-support)开发，为常见的 HOI4 模组文件提供可视化预览和源码跳转能力。
## 功能
- 国策预览：支持 `common/national_focus` 和 `common/continuous_focus`。
- 科技树预览：支持 `common/technologies`。
- GUI 预览：支持 `interface` 目录下的 `.gui` 文件。
- 地图预览：支持地块、省份、国家和战略区域视图。
- 在 Paradox Language Support 能解析的范围内显示本地化名称、图标和详细信息。
- 单击查看详细信息，双击跳转回对应源码文件。
## 环境要求
- IntelliJ IDEA 2026.1 或更高版本。
- 从源码构建需要 Java 21。
- Paradox Language Support v2.1.9。
- 一个 HOI4 模组项目。建议先在 Paradox Language Support 中配置好游戏目录和模组目录，这样 Oiia 能正确定位游戏与模组资源。
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