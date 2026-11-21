# Awakened PoE Trade 中文数据生成工具

为 [Awakened PoE Trade](../README.md) 生成简中物品名称、词缀和词缀机制名称。生成器读取本地游戏导出及上级仓库的 `renderer/public/data/en/`，更新 `renderer/public/data/zh_CN/` 下的 `items.ndjson`、`stats.ndjson`，以及 `client_strings.js` 中标记为生成的 `*_MODS` 区块。不要手动编辑生成区块。

## 导出与生成

在主仓库根目录初始化 Helper 及其嵌套子模块 `PoePatcherCli`、`poe-dat-viewer`：

```sh
git submodule update --init --recursive
```

以下命令均在 Helper 根目录执行。生成翻译需要 JDK（Gradle Wrapper 8.5，JVM 8 toolchain）；导出还需要 Windows PowerShell、.NET 8 SDK、Node.js 20 或更高版本，以及已安装的《流放之路 1》。

1. 在 [`scripts/build_and_export.bat`](scripts/build_and_export.bat) 中设置本机的 `INTL_GAME_PATH` 和 `TENCENT_GAME_PATH`。将符合脚本命名规则的补丁和还原 ZIP 成对放入 `scripts/patch/`。
2. 关闭游戏，运行 `.\scripts\build_and_export.bat`。脚本会修改并还原游戏文件，按[导出模板](data_repo/exported/config.template.json)将数据写入 `data_repo/exported/`。如需先检查模板和 ZIP 配对，运行 `.\scripts\build_and_export.bat --check`；该模式仍会发布补丁器，但不修改游戏文件，也不导出数据。
3. 确认所需导出齐全后，运行 `.\gradlew.bat generateTranslations`（或 `.\scripts\generate_translations.bat`）。此步骤只构建并生成翻译，不会导出游戏数据。

每个数据源下的 `config.json`、`files/` 和 `tables/` 均为本地生成文件。游戏版本或导出模板更新后，请重新导出再生成。

### 数据源配置

[`Main.kt`](src/main/kotlin/Main.kt) 默认使用下列四组配置。导出脚本允许只处理有完整 ZIP 配对的服务器，但生成仍需要当前配置引用的所有导出。

| 源 stat 目录 | 目标目录 | 目标语言键 | 目标 stat 无语言标记时 |
| --- | --- | --- | --- |
| `intl_poedb` | `intl_poedb` | `Traditional Chinese` | `English` |
| `intl_amsco2` | `intl_amsco2` | `Traditional Chinese` | `English` |
| `tencent` | `tencent` | `Simplified Chinese` | `English` |
| `intl_amsco2` | `tencent_amsco2` | `Simplified Chinese` | `Simplified Chinese` |

源 stat 的无标记内容均按 `English` 读取。国际服的 `Traditional Chinese` 是简中补丁使用的语言槽，输出仍写入 `zh_CN`，不会更新 `cmn-Hant`。

## 验证与审计

- `.\gradlew.bat build`：编译并运行测试，不需要游戏导出，也不会生成翻译。
- `.\gradlew.bat generateTranslations --args=--mod-names-only`：只更新 `*_MODS`。
- `.\gradlew.bat generateTranslations --args=--check-mod-names`：核对 `*_MODS` 与当前导出是否一致，不写入翻译文件。
- 修改词缀数据后，在上级仓库的 `renderer/` 中运行 `npm run test:affix-recognition`，验证词缀识别和机制归类。

词缀审计会比较基于交易 ID 的查找与旧文案匹配结果，不改写生成文件：

```powershell
.\gradlew.bat -q generateTranslations --args=--audit-stats --console=plain > audit-stats.ndjson 2> audit-stats.log
```

报告每行一个 JSON 对象，首行记录导出来源，末行汇总统计。用 `location.inputLine`（从 1 开始）、`groupIndex` 和 `matcherIndex`（从 0 开始）定位英文输入。`selected` 和 `alternatives` 记录选中的译文及冲突候选；`legacy`、`output`、`removed` 和 `added` 记录新旧差异。`effect-matcher` 记录技能词缀补齐结果及跳过原因。

手工兼容映射维护在 [`data_repo/extra/`](data_repo/extra/)；新增导出表或列时修改导出模板后重新导出。

## 相关项目

- [DonkiChen/poe-dat-viewer](https://github.com/DonkiChen/poe-dat-viewer)：本工具使用的导出器子模块
- [SnosMe/poe-dat-viewer](https://github.com/SnosMe/poe-dat-viewer)：导出器上游
