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
- 可以运行 POSIX shell 的环境：Linux、macOS，或 Windows 上的 Git Bash/WSL
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

在需要使用的数据源目录中修改 `config.json` 的 `steam` 字段。当前仓库包含以下配置：

- [intl_amsco2/config.json](./data_repo/exported/intl_amsco2/config.json)
- [intl_poedb/config.json](./data_repo/exported/intl_poedb/config.json)
- [tencent/config.json](./data_repo/exported/tencent/config.json)

例如：

```json
{
  "steam": "C:\\Program Files\\Epic Games\\PathOfExile"
}
```

请根据本机的游戏安装位置修改路径。`config.json` 中的路径属于本地环境配置，不要直接复制其他人的路径。

### 2. 选择要导出的游戏数据

打开 [`data_repo/export.sh`](./data_repo/export.sh)，手动取消注释需要导出的数据源，或注释掉不需要的数据源。

当前脚本默认启用 `intl_amsco2`，其他数据源默认被注释：

```sh
(cd exported/intl_amsco2 && node ../../../poe-dat-viewer/lib/dist/cli/run.js)
#(cd exported/intl_poedb && node ../../../poe-dat-viewer/lib/dist/cli/run.js)
#(cd exported/tencent && node ../../../poe-dat-viewer/lib/dist/cli/run.js)
```

然后从 `data_repo` 目录执行导出脚本：

```bash
cd data_repo
sh export.sh
cd ..
```

脚本会先编译 `poe-dat-viewer/lib`，再根据已启用的配置导出游戏文件。导出结果位于对应数据源目录的 `files/` 和 `tables/` 中。

### 3. 选择 Main.kt 使用的数据

打开 [`src/main/kotlin/Main.kt`](./src/main/kotlin/Main.kt)，手动配置一个或多个 `GameDataRepo.prepareMapper(...)`。

当前默认配置为使用 `intl_amsco2` 数据源，并以繁体中文作为目标语言：

```kotlin
GameDataRepo.prepareMapper(
    sourceBaseDirName = "intl_amsco2",
    targetBaseDir = "intl_amsco2",
    targetLang = "Traditional Chinese",
    targetStatDefaultLang = "English"
)
```

这几个参数的含义如下：

- `sourceBaseDirName`：英文源数据所在的数据源目录。
- `targetBaseDir`：目标语言数据所在的数据源目录。
- `targetLang`：目标数据中的语言名称，必须与 `config.json` 的 `translations` 配置一致。
- `targetStatDefaultLang`：目标词缀描述的默认语言，通常为 `English`。

如果需要生成国服简体中文数据，需要确保 `tencent` 数据已经导出，并根据实际数据修改 `targetBaseDir` 和 `targetLang`。如果需要同时使用多个数据源，可以保留多个 `prepareMapper(...)` 调用。

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

生成的 `items.ndjson` 和 `stats.ndjson` 会直接覆盖 APT 项目中的 `zh_CN` 数据。实际写入的语言取决于 `Main.kt` 中配置的 `targetLang`。运行工具前请确认 APT 英文输入文件存在，并在需要时备份现有的中文输出文件。

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
│   ├── extra/      # 手工补充的词缀映射
│   └── export.sh   # 编译导出工具并导出游戏数据
├── poe-dat-viewer/ # 游戏数据导出工具，Git submodule
├── build.gradle.kts
└── gradlew.bat
```

`data_repo/exported/*/files` 和 `data_repo/exported/*/tables` 是本地生成数据，默认不会提交到 Git。切换机器或游戏版本后，需要重新检查 `config.json` 并重新导出。

## 当前限制

- 游戏目录需要手动写入每个数据源的 `config.json`。
- `export.sh` 当前需要手动选择要导出的数据源，并且数据补丁的选择也需要维护者自行确认。
- `Main.kt` 当前需要手动选择要使用的数据源、目标目录和目标语言。
- 生成过程依赖具体游戏版本；如果导出的游戏数据与 APT 当前数据版本不匹配，可能出现缺少翻译或名称无法匹配的情况。
- 项目当前没有 Gradle `run` 任务，需要通过 IDE 运行 `Main.main()`。

## 开发与验证

在辅助工具根目录执行：

```powershell
.\gradlew.bat build
```

如修改了数据导出流程，请在构建前重新执行 `sh data_repo/export.sh`，并检查生成的 `items.ndjson` 和 `stats.ndjson` 是否包含预期翻译。

## 相关项目与致谢

- [Awakened PoE Trade](../README.md)
- [DonkiChen/poe-dat-viewer](https://github.com/DonkiChen/poe-dat-viewer)：本项目使用的修改版子模块
- [SnosMe/poe-dat-viewer](https://github.com/SnosMe/poe-dat-viewer)：上游项目

感谢 Awakened PoE Trade 和 poe-dat-viewer 项目的维护者。
