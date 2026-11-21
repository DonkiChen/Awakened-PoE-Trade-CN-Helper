# Awakened PoE Trade 中文数据生成工具

这个工具用于为 [Awakened PoE Trade](../README.md) 生成中文物品名称和词缀数据。
它会读取《流放之路》的游戏数据，为 APT 生成简中 `items.ndjson`、`stats.ndjson`，以及解析器用来识别词缀所属机制的 `client_strings.js` 中的 `*_MODS` 区块。

> 这是一个面向维护者和翻译数据生成的开发工具，不是独立运行的 APT 客户端。

## 功能概览

- 从 PoE 游戏文件中导出词缀描述、物品名称、技能名称等数据。
- 根据游戏数据中的 ID 和名称建立翻译映射，减少手工维护翻译表的工作量。
- 翻译 APT 的物品数据和词缀数据，同时尽量保留原始 NDJSON 结构。
- 支持通过多个数据源生成映射；当前使用的数据源和目标语言在 `Main.kt` 中配置。

## 工作流程

```text
PoE 游戏安装目录
        |
        v
scripts/build_and_export.bat 导出游戏数据
        |
        v
data_repo/exported/<data-source>/{files,tables}
        |
        v
scripts/generate_translations.bat（Gradle build + Main.kt）
        |
        v
APT renderer/public/data/zh_CN/
  items.ndjson、stats.ndjson、client_strings.js（仅 *_MODS）
```

## 前置要求

- Git
- JDK（Gradle Wrapper 为 8.5；Kotlin 使用 JVM 8 toolchain）
- Node.js `>= 20`
- Windows PowerShell（导出脚本使用 Windows 批处理和 PowerShell）
- 已安装 Path of Exile 1，并能够访问需要导出的游戏数据

Node.js `>= 20` 是 `poe-dat-viewer/lib` 当前依赖声明的最低版本。项目自带 Gradle Wrapper，不需要单独安装 Gradle。

## 快速开始

首次克隆主仓时使用 `--recurse-submodules`，确保一并获取 `poe-dat-viewer`。如果主仓已经克隆，在主仓根目录初始化子模块：

```bash
git submodule update --init --recursive
```

除子模块初始化命令外，以下操作均在 `Awakened-PoE-Trade-CN-Helper` 根目录执行。

### 1. 配置游戏目录

使用 [`scripts/build_and_export.bat`](./scripts/build_and_export.bat) 时，在脚本开头修改本机的游戏路径变量：

```bat
set "INTL_GAME_PATH=C:\Program Files\Epic Games\PathOfExile"
set "TENCENT_GAME_PATH=C:\Program Files (x86)\流放之路(511)"
```

国际服和国服共用 [config.template.json](./data_repo/exported/config.template.json)。脚本会填入游戏路径和目标语言（国际服为 `Traditional Chinese`，国服为 `Simplified Chinese`），生成各数据源目录中的 `config.json`。两者都保留 `English`。模板已包含 `Mods` 表及其 `Id`、`Name` 列；如果旧导出缺少这张表，请用新模板重新导出。生成的配置属于本地环境配置，不要直接复制其他人的路径。

### 2. 自动导出游戏数据

将补丁 ZIP 放入 [`scripts/patch`](./scripts/patch)，关闭正在运行的游戏，然后执行：

```powershell
.\scripts\build_and_export.bat
```

仅检查补丁器发布、配置模板文件是否存在及补丁包配对时，可执行 `.\scripts\build_and_export.bat --check`；该模式会发布补丁器，但会跳过游戏文件修改和数据导出。

脚本会自动编译补丁器和 `poe-dat-viewer`，根据 ZIP 配对选择可用的数据源，完成补丁、导出和还原流程。国服会连续导出还原数据到 `tencent`，再导出功能补丁数据到 `tencent_amsco2`，最后还原游戏。生成的 `config.json` 会保留在各数据源目录中，但不会提交到 Git。

### 3. 选择 Main.kt 使用的数据

打开 [`src/main/kotlin/Main.kt`](./src/main/kotlin/Main.kt)，通过一个或多个 `GameDataRepo.prepareMapper(...)` 配置本次生成使用的数据源和目标语言。当前代码配置如下：

| 源数据目录 | 目标数据目录 | 目标语言 | 无语言标记的目标 stat 语言 |
| --- | --- | --- | --- |
| `intl_poedb` | `intl_poedb` | `Traditional Chinese` | `English` |
| `intl_amsco2` | `intl_amsco2` | `Traditional Chinese` | `English` |
| `tencent` | `tencent` | `Simplified Chinese` | `English` |
| `intl_amsco2` | `tencent_amsco2` | `Simplified Chinese` | `Simplified Chinese` |

这几个参数的含义如下：

- `sourceExportDirName`：`data_repo/exported` 下提供源 stat 描述的数据目录名，通常包含英文文案。
- `targetExportDirName`：`data_repo/exported` 下提供目标 table 和 stat 描述的数据目录名。
- `targetLanguageKey`：目标数据中的语言 key，必须与 `config.json` 的 `translations` 配置一致。
- `sourceStatUnlabelledLanguage`：源 stat 描述中没有 `lang` 标记的内容所属语言，通常为 `English`。
- `targetStatUnlabelledLanguage`：目标 stat 描述中没有 `lang` 标记的内容所属语言。当前 `tencent` 数据开头包含无标记英文内容，因此设为 `English`；`tencent_amsco2` 的无标记 stat 内容为简体中文，因此设为 `Simplified Chinese`。

当前 `Main.kt` 同时配置国际服 `Traditional Chinese` 语言键和国服 `Simplified Chinese` 语言键的数据。调整数据源时，请确认相应的导出目录已生成，并为每个 mapper 设置正确的语言参数。

### 4. 构建并生成翻译

完成游戏数据导出后，在辅助工具根目录运行：

```powershell
.\scripts\generate_translations.bat
```

脚本会调用 Gradle 的 `generateTranslations` 任务。该任务先执行 `build`，再运行 `Main.kt`，无需在 IntelliJ IDEA 中手动运行；工作目录为辅助工具根目录，因此相对数据路径可以正确解析。此脚本只构建并生成翻译，不会自动执行前一步的游戏数据导出。也可以在辅助工具根目录直接运行：

```powershell
.\gradlew.bat generateTranslations
```

### 5. 单独更新简中词缀机制名称

完整生成流程已包含这一步。若只需要更新或校验词缀机制名称，不改动 `items.ndjson` 和 `stats.ndjson`：

```powershell
.\gradlew.bat generateTranslations --args=--mod-names-only
.\gradlew.bat generateTranslations --args=--check-mod-names
```

更新命令只改写 `../renderer/public/data/zh_CN/client_strings.js` 中标记为生成的 `*_MODS` 区块，校验命令不写入翻译文件。生成器从英文 `client_strings.js` 读取机制分类，在每份导出中按英文 `Name` 匹配所有词缀，再用 `Id` 关联到中文 `Name`，最后合并并去重。不合并旧版译名，只使用当前导出中的词缀名称。

每个 mapper 都从 `data_repo/exported/<targetExportDirName>/tables/` 读取 `English/Mods.json` 和 `<targetLanguageKey>/Mods.json`，不使用 `sourceExportDirName`。国际服导出中的 `Traditional Chinese` 是简中补丁使用的语言槽，不会用于更新 `cmn-Hant`。这里的“当前导出”指本机最近一次导出的游戏数据，不是运行时联网更新；游戏更新后应先重新导出，再生成翻译。不要手改生成区块。

## 输出文件

完整生成流程读取上级 APT 项目 `renderer/public/data/en/` 中的对应英文文件，并更新 `renderer/public/data/zh_CN/`：

| 文件 | 更新范围 |
| --- | --- |
| `items.ndjson` | 整个文件 |
| `stats.ndjson` | 整个文件 |
| `client_strings.js` | 只替换 `BEGIN GENERATED MOD NAMES` 和 `END GENERATED MOD NAMES` 之间的 `*_MODS` |

`Main.kt` 中的 `targetLanguageKey` 只决定读取导出中的哪个语言目录，不改变 renderer 的输出目录。运行工具前请确认英文输入和简中 `client_strings.js` 都存在。

### 审计词缀生成

普通交易词缀优先使用英文 APT 数据里的 `trade.ids`。生成器从 CSD 的有序内部 `statIds` 计算 trade hash，再在对应候选中用英文 matcher 区分规则变体。中文文本仍来自 CSD。不同导出对同一内部 stat 有各自唯一的译名时，会保留这些别名；按 APT `ref` 明确维护的兼容别名也保留。同一导出内有歧义时报告冲突，只保留本导出中能被该 ID 验证的旧译文。无法验证时保留英文 matcher，不从全局文案表随意选一个。普通 ID 无法匹配时使用旧路径；固定选项、pseudo 和永恒珠宝等保留原来的专用处理。

在 Helper 根目录运行以下命令，可用同一批当前导出逐条对比 ID 路径和旧文案路径。审计只输出报告，不改写 `stats.ndjson`、物品数据、客户端字符串或游戏文件。

```bat
gradlew.bat -q generateTranslations --args=--audit-stats --console=plain > audit-stats.ndjson 2> audit-stats.log
```

报告每行是一个 JSON 对象；首行是导出来源，末行是统计。用 `location.inputLine`（从 1 开始）、`groupIndex` 和 `matcherIndex`（从 0 开始）定位英文输入。`selected` 列出 ID 验证通过的译文和来源；`alternatives` 列出冲突候选；`legacy`、`output`、`removed` 和 `added` 记录旧、新结果和差异。CSD 来源含导出名、文件名、行号和内部 stat ID；非 CSD 的旧译文只列出 mapper。

`effect_*` 词缀的缺失写法由客户端数据补齐，不依赖 `trade_data/` 快照。生成器按游戏 ID 关联 `ActiveSkills` 或 `BaseItemTypes` 中的英中技能名，再填入 `ClientStrings` 中的赋予技能或镶嵌辅助模板。只接受唯一对应现有英文 APT matcher 和游戏 ID、且两端 `#` 数量一致的结果，保留原有交易 ID、固定值和译文。补齐结果和跳过原因以 `effect-matcher` 记录写入审计报告；交易快照只用于审计时核对 ID 是否存在。

首次验证（2026-11-21，使用上文配置的四组本地导出）：共 12,741 条英文 matcher，其中 8,632 条进入普通 ID 查找，7,843 条直接使用 ID 候选译文（90.9%），76 条有冲突但保留了经 ID 验证的旧译文，711 条走旧路径，2 条缺译。另有 4,109 条不适用普通 ID 查找，其中 2,238 条带 `|option` 的 matcher 全部不变。新旧对比剔除了 409 条 matcher 上的 416 个旧变体；报告将这些变体定位到不同的内部 stat，76 条 matcher 的首选变体随之改变。没有新增缺译，也没有改变 matcher 或查询元数据。更新导出后请重新测量，不要将此基线当作固定覆盖率。

## `.csd` 规则

`StatDescriptionParser.kt` 读取规则，`CsdRuleEngine.kt` 求值并生成 matcher，`CsdMatcherTranslations.kt` 负责英中配对。在 `CsdGameDirectives.kt` 注册新的数值转换、查表或元数据指令即可扩展，不需要修改文件解析器。查表指令的参数是从 1 开始的 stat 索引；模板则从 `{0}` 开始编号。查表的第一项参数是输出槽，其余参数可参与过滤（例如永恒珠宝的阵营和内部修订号）。

动态 matcher 根据 stat 值域配对；能覆盖同一变体的旧版字面规则也可以作为译文。不支持的指令、缺表、冲突或只覆盖部分值域的动态译文会报告并跳过。普通规则保留旧的文案归一化和配对行为。独立的 `include` 不递归展开，`reminderstring` 等元数据也不拼入 matcher。

随机宝石、天赋、鱼饵和地图首领等词缀会按 CSD 指令查游戏表。星团珠宝的小天赋词缀使用 `PassiveTreeExpansionSkills`、`PassiveSkills` 和 `Stats` 的数值，再用 CSD 生成实际文本。升级后请用新模板重新导出，不要直接复用缺少这些表或列的旧导出。

`extra_stats.json` 仍保留游戏文件未覆盖的交易站伪词缀，以及当前导出无法替代的兼容译文。

如果已在市集验证另一组内部属性也能用现有交易 ID 筛选，可在 `data_repo/extra/trade_stat_csd_aliases.json` 声明完整交易 ID 和有序的 CSD `sourceStatIds`。生成器只从指定属性组中提取与英文 matcher 匹配的译文，不需要复制中文，也不会新增交易 ID。希望之线的全抗使用此方式。

半径指令读取各语言的 `JewelRadius*` 字符串，并保留 CSD 中同值的字面旧译。鱼钩、塑界者技能、召唤物翻倍、物品类别、惊悸奖励、风暴词缀及武器天赋传奇基底的查表指令需要新版导出模板中的表或列；旧导出仍可运行，但会报告缺失数据并跳过相应规则。`tree_expansion_jewel_passive` 仍由上面的星团珠宝生成流程覆盖，不把珠宝尺寸表误用作天赋文案。

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
├── scripts/        # 数据导出、翻译生成脚本及补丁包目录
├── PoePatcherCli/  # 游戏补丁工具
├── poe-dat-viewer/ # 游戏数据导出工具，Git submodule
├── trade_data/     # 从 trade 拉取的 en/cn JSON 数据
├── gradle/wrapper/ # Gradle Wrapper 配置
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew.bat
```

`data_repo/exported/*/files` 和 `data_repo/exported/*/tables` 是本地生成数据，默认不会提交到 Git。切换机器或游戏版本后，需要重新检查 `config.json` 并重新导出。

## 当前限制

- 脚本中的游戏目录需要根据本机环境手动配置。
- `build_and_export.bat` 依赖补丁 ZIP 文件名匹配脚本中的规则；如果补丁命名发生变化，需要同步更新脚本。
- `Main.kt` 当前需要手动选择要使用的数据源、目标目录和目标语言。
- 生成过程依赖具体游戏版本；如果导出的游戏数据与 APT 当前数据版本不匹配，可能出现缺少翻译或名称无法匹配的情况。

## 开发与验证

在辅助工具根目录执行基础构建和检查：

```powershell
.\gradlew.bat build
```

`build` 只编译项目并运行配置的检查，不会执行 `Main.kt`。要验证翻译生成流程，请确保游戏数据已导出，然后运行：

```powershell
.\scripts\generate_translations.bat
```

如修改了数据导出流程，先运行 `scripts/build_and_export.bat` 完成导出，再运行上述生成脚本并检查三个输出的差异。可用 `.\gradlew.bat generateTranslations --args=--check-mod-names` 核对简中 `*_MODS` 与当前导出是否一致，再到上级项目的 `renderer` 目录运行 `npm run test:affix-recognition`，验证词缀解析和机制归类。

## 相关项目与致谢

- [Awakened PoE Trade](../README.md)
- [DonkiChen/poe-dat-viewer](https://github.com/DonkiChen/poe-dat-viewer)：本项目使用的修改版子模块
- [SnosMe/poe-dat-viewer](https://github.com/SnosMe/poe-dat-viewer)：上游项目

感谢 Awakened PoE Trade 和 poe-dat-viewer 项目的维护者。
