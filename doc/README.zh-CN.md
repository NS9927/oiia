# Oiia

[English](../README.md)

Oiia 是一个面向 IntelliJ IDEA 的 Hearts of Iron IV 模组开发工具包，目前处于 beta 测试阶段。
它基于 [Paradox Language Support](https://plugins.jetbrains.com/plugin/16825-paradox-language-support)
构建，为常见的 HOI4 模组文件提供可视化预览和源码跳转能力。

## 功能

- 国策预览：支持 `common/national_focus` 和 `common/continuous_focus`。
- 科技树预览：支持 `common/technologies`。
- GUI 预览：支持 `interface` 目录下的 `.gui` 文件。
- 地图预览：支持地块、省份、国家和战略区域视图。
- 在 Paradox Language Support 能解析的范围内显示本地化名称、图标和详细信息。
- 单击查看详情，双击跳转回对应源码文件。

## 环境要求

- IntelliJ IDEA 2026.1.1 或更高版本。
- 从源码构建需要 Java 21。
- Paradox Language Support 2.1.9。
- 一个 HOI4 模组项目。建议先在 Paradox Language Support 中配置好游戏目录和模组目录，这样 Oiia 能正确定位游戏与模组资源。

## 安装 Beta 版本

1. 从 GitHub Releases 下载最新的 `oiia-*.zip` 文件。
2. 打开 IntelliJ IDEA。
3. 进入 `Settings | Plugins | Install Plugin from Disk...`。
4. 选择下载的 zip 文件，然后重启 IDE。

这是 beta 测试版本。部分预览信息可能还不完整，大型模组中的地图和 GUI 预览也可能需要较长加载时间。

## 从源码构建

```powershell
.\gradlew.bat buildPlugin
```

生成的插件 zip 位于 `../build/distributions`。

开发时常用命令：

```powershell
.\gradlew.bat runIde
.\gradlew.bat build
```

## 使用提示

- 打开国策、科技或 GUI 文件后，对应预览工具窗口会根据当前文件自动刷新。
- 地图预览会尝试从项目目录以及 Paradox Language Support 配置的 HOI4 游戏/模组目录读取地图资源。
- 如果图标、贴图或本地化没有显示，优先检查 Paradox Language Support 的项目配置是否正确。
- 预览中的节点或地图对象通常可以单击查看详情，双击跳转到来源文件。

## 项目状态

Oiia 目前专注于 HOI4 模组开发中的预览和导航工作流。第一个 beta 版本主要用于收集手动测试反馈、问题报告，以及确认哪些预览能力最值得继续完善。

欢迎在这里反馈 beta 问题：

https://github.com/NS9927/oiia/issues

## 许可证

MIT。见 [LICENSE](../LICENSE)。
