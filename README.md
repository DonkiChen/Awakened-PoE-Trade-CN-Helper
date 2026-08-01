# Awakened PoE Trade 中文数据生成工具

这个工具用于为 [Awakened PoE Trade](../README.md) 生成中文物品名称和词缀数据。
它会读取《流放之路》的游戏数据，建立英文名称与目标语言名称之间的映射，然后将翻译结果写入 APT 的 `items.ndjson` 和 `stats.ndjson`。

> 这是一个面向维护者和翻译数据生成的开发工具，不是独立运行的 APT 客户端。

## 功能概览

- 从 PoE 游戏文件中导出词缀描述、物品名称、技能名称等数据。
- 根据游戏数据中的 ID 和名称建立翻译映射，减少手工维护翻译表的工作量。
- 翻译 APT 的物品数据和词缀数据，同时尽量保留原始 NDJSON 结构。
- 支持通过多个数据源生成映射，具体使用哪些数据源由维护者手动选择。

## 工作流程

```text
PoE 游戏安装目录
        |
        v
poe-dat-viewer 导出游戏数据
        |
        v
data_repo/exported/<data-source>/{files,tables}
        |
        v
Main.kt 生成英文到目标语言的映射
        |
        v
APT renderer/public/data/zh_CN/{items,stats}.ndjson
```

## 前置要求

- Git
- JDK（项目使用 JVM 8 toolchain）
- Node.js `>= 20`
- Windows PowerShell（导出脚本使用 Windows 批处理和 PowerShell）
- 已安装 Path of Exile 1，并能够访问需要导出的游戏数据

Node.js `>= 20` 是 `poe-dat-viewer/lib` 当前依赖声明的最低版本。项目自带 Gradle Wrapper，不需要单独安装 Gradle。

## 快速开始

以下命令均以 `Awakened-PoE-Trade-CN-Helper` 为当前工作目录。首次克隆时建议递归初始化子模块：

```bash
git clone --recurse-submodules <repository-url>
cd Awakened-PoE-Trade-Simplified-Chinese/Awakened-PoE-Trade-CN-Helper
```

如果仓库已经克隆完成：

```bash
git submodule update --init --recursive
```

### 1. 配置游戏目录

使用 [`scripts/build_and_export.bat`](./scripts/build_and_export.bat) 时，在脚本开头修改本机的游戏路径变量：

```bat
set "INTL_GAME_PATH=C:\Program Files\Epic Games\PathOfExile"
set "TENCENT_GAME_PATH=C:\Program Files (x86)\流放之路(511)"
```

导出配置模板如下：

- [intl_config.json.template](./data_repo/exported/intl_config.json.template)
- [tencent_config.json.template](./data_repo/exported/tencent_config.json.template)

脚本会根据模板生成各数据源目录中的 `config.json`。生成的配置属于本地环境配置，不要直接复制其他人的路径。

### 2. 自动导出游戏数据

将补丁 ZIP 放入 [`scripts/patch`](./scripts/patch)，关闭正在运行的游戏，然后执行：

```powershell
.\scripts\build_and_export.bat
```

仅检查发布工具、配置和补丁包匹配时，可执行 `.\scripts\build_and_export.bat --check`；该模式不会修改游戏文件。

脚本会自动编译补丁器和 `poe-dat-viewer`，根据 ZIP 配对选择可用的数据源，完成补丁、导出和还原流程。国服会连续导出还原数据到 `tencent`，再导出功能补丁数据到 `tencent_amsco2`，最后还原游戏。生成的 `config.json` 会保留在各数据源目录中，但不会提交到 Git。

### 3. 选择 Main.kt 使用的数据

打开 [`src/main/kotlin/Main.kt`](./src/main/kotlin/Main.kt)，手动配置一个或多个 `GameDataRepo.prepareMapper(...)`。

当前默认配置为使用 `intl_amsco2` 数据源，并以繁体中文作为目标语言：

```kotlin
GameDataRepo.prepareMapper(
    sourceExportDirName = "intl_amsco2",
    targetExportDirName = "intl_amsco2",
    targetLanguageKey = "Traditional Chinese",
    sourceStatUnlabelledLanguage = "English",
    targetStatUnlabelledLanguage = "English"
)
```

这几个参数的含义如下：

- `sourceExportDirName`：`data_repo/exported` 下提供源 stat 描述的数据目录名，通常包含英文文案。
- `targetExportDirName`：`data_repo/exported` 下提供目标 table 和 stat 描述的数据目录名。
- `targetLanguageKey`：目标数据中的语言 key，必须与 `config.json` 的 `translations` 配置一致。
- `sourceStatUnlabelledLanguage`：源 stat 描述中没有 `lang` 标记的内容所属语言，通常为 `English`。
- `targetStatUnlabelledLanguage`：目标 stat 描述中没有 `lang` 标记的内容所属语言。国际服通常为 `English`，国服中文-only 导出通常为 `Simplified Chinese`。

如果需要生成国服简体中文数据，需要确保 `tencent` 和 `tencent_amsco2` 数据已经导出，并保留类似下面的 mapper：

```kotlin
GameDataRepo.prepareMapper(
    sourceExportDirName = "tencent",
    targetExportDirName = "tencent_amsco2",
    targetLanguageKey = "Simplified Chinese",
    sourceStatUnlabelledLanguage = "English",
    targetStatUnlabelledLanguage = "Simplified Chinese"
)
```

如果需要同时使用多个目标数据源，可以保留多个 `prepareMapper(...)` 调用；每个 mapper 可以独立指定目标语言和 stat 默认语言。

### 4. 构建并运行

先构建 Kotlin 项目：

```powershell
.\gradlew.bat build
```

然后在 IntelliJ IDEA 等 Kotlin 开发环境中运行 [`Main.kt`](./src/main/kotlin/Main.kt) 的 `Main.main()`。

运行 `Main.main()` 时，请将工作目录设置为 `Awakened-PoE-Trade-CN-Helper` 根目录。项目中的数据路径和 APT 输出路径都是相对路径，工作目录错误会导致找不到导出数据或 APT 输入文件。

## 输出文件

工具会读取上级 APT 项目的英文数据，并生成以下文件：

```text
../renderer/public/data/en/items.ndjson
../renderer/public/data/en/stats.ndjson
        |
        v
../renderer/public/data/zh_CN/items.ndjson
../renderer/public/data/zh_CN/stats.ndjson
```

生成的 `items.ndjson` 和 `stats.ndjson` 会直接覆盖 APT 项目中的 `zh_CN` 数据。实际写入的语言取决于 `Main.kt` 中配置的 `targetLanguageKey`。运行工具前请确认 APT 英文输入文件存在，并在需要时备份现有的中文输出文件。

## 项目结构

```text
.
├── src/main/kotlin/
│   ├── data/       # 游戏数据和 APT 数据的读取、解析
│   ├── item/       # 物品名称翻译和输出
│   ├── stat/       # 词缀翻译和输出
│   └── util/       # NDJSON、JSON 等通用工具
├── data_repo/
│   ├── exported/   # 各数据源的配置和本地导出结果
│   └── extra/      # 手工补充的词缀映射
├── poe-dat-viewer/ # 游戏数据导出工具，Git submodule
├── build.gradle.kts
└── gradlew.bat
```

`data_repo/exported/*/files` 和 `data_repo/exported/*/tables` 是本地生成数据，默认不会提交到 Git。切换机器或游戏版本后，需要重新检查 `config.json` 并重新导出。

## 当前限制

- 脚本中的游戏目录需要根据本机环境手动配置。
- `build_and_export.bat` 依赖补丁 ZIP 文件名匹配脚本中的规则；如果补丁命名发生变化，需要同步更新脚本。
- `Main.kt` 当前需要手动选择要使用的数据源、目标目录和目标语言。
- 生成过程依赖具体游戏版本；如果导出的游戏数据与 APT 当前数据版本不匹配，可能出现缺少翻译或名称无法匹配的情况。
- 项目当前没有 Gradle `run` 任务，需要通过 IDE 运行 `Main.main()`。

## 开发与验证

在辅助工具根目录执行：

```powershell
.\gradlew.bat build
```

如修改了数据导出流程，请在构建前重新执行 `.\scripts\build_and_export.bat`，并检查生成的 `items.ndjson` 和 `stats.ndjson` 是否包含预期翻译。

## 相关项目与致谢

- [Awakened PoE Trade](../README.md)
- [DonkiChen/poe-dat-viewer](https://github.com/DonkiChen/poe-dat-viewer)：本项目使用的修改版子模块
- [SnosMe/poe-dat-viewer](https://github.com/SnosMe/poe-dat-viewer)：上游项目

感谢 Awakened PoE Trade 和 poe-dat-viewer 项目的维护者。
