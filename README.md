# Automatic XMake2CMake

`version 1.0-SNAPSHOT`

CLion plugin that keeps `CMakeLists.txt` in sync with `xmake.lua`.

CLion 插件：从 `xmake.lua` 自动生成并同步 `CMakeLists.txt`。

Plugin ID: `Automatic-XMake2CMake`

这是一个由AI编写的懒人插件，目的很简单：**当项目内任意位置的xmake.lua**被手动更改时，自动在同一目录下生成CMakeLists.txt

本插件旨在与[XMake](https://plugins.jetbrains.com/plugin/17406-xmake)一同协作，因此即使插件提供了配置其他xmake、xmake参数的能力，但默认采用XMake插件的xmake path。
且虽然本插件能独立运行，你**最好**和XMake插件一起使用。

提供了三种模式：
1. 每次生成前询问
2. 每次生成前无需询问
3. 不自动生成

因为xmake插件目前没有深度集成到CLion中，调试、代码补全等工作仍然要配合cmake来完成。

生成的时机：
1. xmake.lua被更新，且在当前xmake.lua的页面摁下ctrl+s
2. xmake.lua被更新，且关闭了xmake.lua的tab时
3. xmake.lua没有更新，通过右键—force update cmakelist.txt（默认配置ctrl+s）
- 特别注意，在项目被关闭时，外部更改xmake.lua不会被记录，但可以通过(3.)
来进行强制更新，或者直接调用xmake插件的update cmakelists.txt
- 同时，插件只记录你在关闭xmake.lua前的所有变更，关闭xmake.lua后，插件不记录变更

This is a "lazy person" plugin written by AI. Its goal is simple: whenever an `xmake.lua`
**anywhere in the project** is manually modified, generate `CMakeLists.txt` in the same directory automatically.

This plugin is intended to work in collaboration with XMake. Therefore, even though the plugin provides the ability to configure other xmake and xmake parameters, it uses the XMake plugin's xmake path by default.

Also, although this plugin can run independently, you had better use it together with the XMake plugin.

Three modes are provided:
1. Ask for confirmation before every automatic generation
2. Generate automatically, without asking
3. Disable automatic generation

Because the XMake plugin is not deeply integrated into CLion yet, debugging and code completion
still have to be done together with CMake.

When the generation is triggered:
1. `xmake.lua` was updated, and you press Ctrl+S while the editor of that `xmake.lua` is focused
2. `xmake.lua` was updated, and you close the tab of that `xmake.lua`
3. `xmake.lua` was not updated: use the context menu item "Force Update CMakeLists.txt"(default ctrl+s keyboard too)
- Note: while the project is closed, external changes to `xmake.lua` are not tracked. You can still
  force an update with (3), or by invoking the XMake plugin's "Update CMakeLists" action directly.
- Also, the plugin only takes the changes you made before closing `xmake.lua` into account; once
  `xmake.lua` is closed, changes are not tracked.

> 重要内容在上方^^^^^ | vvvvv 下方内容为AI生成，除非你打算修改本插件，否则这些信息可能没用

## 1. Packages and versions / 使用的包与版本

### 1.1 Build plugins / 构建插件

| Package | Version | Notes / 说明 |
|---|---|---|
| `org.jetbrains.intellij.platform` | 2.19.0 | IntelliJ Platform Gradle Plugin；负责平台 SDK 依赖、`buildPlugin`、`verifyPlugin` 等任务 |
| `org.jetbrains.kotlin.jvm` | 2.4.20 | Kotlin JVM Gradle 插件 |
| `org.gradle.toolchains.foojay-resolver-convention` | 0.8.0 | `settings.gradle` 中声明，用于解析 Java toolchain |
| `java` | provided by Gradle | Gradle 内置插件 |

### 1.2 Dependencies / 依赖

| Package / coordinate | Version | Scope | Notes / 说明 |
|---|---|---|---|
| `com.jetbrains.plugins:io.xmake` | 1.4.23 | IntelliJ Platform plugin dependency, optional | 已有的 XMake 插件（JetBrains Marketplace 发布物），见 1.4 |
| IntelliJ Platform SDK, `local("D:/JetBrains/CLion 2026.2.0.1")` | CLion 2026.2.0.1, build `CL-262.8665.321` | compile / run | 使用本机已安装的 CLion 作为编译 SDK，只读引用，不写入该目录 |
| `org.jetbrains.kotlin:kotlin-test` | provided by the Kotlin Gradle Plugin (2.4.20) | test | 单元测试；测试任务使用 JUnit Platform |

### 1.3 Toolchain and external tools / 工具链与外部工具

| Item | Version | Notes / 说明 |
|---|---|---|
| Gradle | 9.3.0 | 构建工具；IntelliJ Platform Gradle Plugin 2.19.0 要求 Gradle 9.0 及以上 |
| Java toolchain | 25 | 编译使用；本机由 CLion 自带的 JetBrains Runtime 25.0.3 提供 |
| xmake CLI | 3.1.1 | 运行期由插件调用的外部程序，非 Gradle 依赖；来源为 XMake 插件配置的 toolkit 或系统 `PATH` |
| `xmake.lua` project description | 2.x/3.x syntax | 被处理的工程描述文件 |
| IntelliJ Platform `since-build` | 262 | `src/main/resources/META-INF/plugin.xml` 中的兼容下限 |

### 1.4 XMake plugin used / 对应的 XMake 插件版本

| Item | Value |
|---|---|
| Plugin ID | `io.xmake` |
| Plugin name | XMake |
| Version | 1.4.23 |
| Published coordinate | `com.jetbrains.plugins:io.xmake:1.4.23` (JetBrains Marketplace) |
| Marketplace page | https://plugins.jetbrains.com/plugin/17406-xmake |
| Declared in | `gradle.properties` (`xmakePluginId`, `xmakePluginVersion`), `build.gradle`, and `src/main/resources/META-INF/plugin.xml` |
| Dependency form | `<depends optional="true" config-file="xmake-integration.xml">io.xmake</depends>` |
| Version requirements of 1.4.23 | IntelliJ Platform 2026.2 (`since-build="262"`), JDK 25 |
| How it is used | 仅作为可选依赖；运行期通过反射读取该插件中已配置的 toolkit 路径（即 xmake 可执行文件位置），未复制或修改其任何代码 |
| Verified against | CLion 2026.2.0.1 (build `CL-262.8665.321`) on Windows |

## 2. XMake open-source information / XMake 开源信息

### 2.1 Involved projects / 涉及的项目

| Project | Repository | License | Homepage | Copyright notice |
|---|---|---|---|---|
| xmake | https://github.com/xmake-io/xmake | Apache License 2.0 | https://xmake.io | `Copyright (C) 2015-present Ruki Wang, https://xmake.io` (per the xmake command line banner) |
| xmake-idea | https://github.com/xmake-io/xmake-idea | Apache License 2.0 | https://xmake.io | `Copyright (C) 2015-present, XMake Open Source Community.` (per the source file headers of the plugin) |
| xmake-repo | https://github.com/xmake-io/xmake-repo | Apache License 2.0 | https://packages.xmake.io | Official package repository; accessed by the xmake CLI at runtime when a project requires packages, not used directly by this plugin |

License texts: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0
许可证：Apache License 2.0，全文见 https://www.apache.org/licenses/LICENSE-2.0

### 2.2 How the open-source components are used / 开源组件的使用方式

| Usage | Detail / 说明 |
|---|---|
| xmake CLI invocation | 运行期通过公开命令行调用：`xmake f -y`（校验工程描述）与 `xmake project -k cmake -y`（生成 `CMakeLists.txt`） |
| xmake-idea plugin | 以 JetBrains Marketplace 发布物形式声明为可选依赖；其插件包由 Gradle 下载到本机 Gradle 缓存，不写入 IDE 安装目录；仅通过反射读取其 toolkit 配置 |
| xmake-repo | 本插件不直接访问；当被处理的工程声明依赖包时，由 xmake 自身按其默认行为访问 |
| Source code reuse | 本仓库不包含、也不修改 xmake、xmake-idea 或 xmake-repo 的源码 |
| Trademarks and affiliation | `XMake`、`xmake`、`xmake.io` 等名称与标识归其各自权利人所有；本项目与 xmake-io 组织无从属或背书关系 |
