# Path of Exile 1 国服 APT 词缀/物品翻译工具

## 工作原理

通过解包 POE 国际服和国服游戏文件，解析并生成中英文词缀/物品的映射关系，然后直接对 APT 中的 stats.ndjson 和 items.ndjson 内容进行逐条替换，生成对应的简体中文词缀和物品名称。

## 技术架构

- 核心语言：Kotlin
- 数据处理：使用 Gson 进行 JSON 解析
- 子模块：
  - [poe-dat-viewer(魔改版, 支持简中)](https://github.com/DonkiChen/poe-dat-viewer)

## 项目结构

- src/main/kotlin/ - 核心代码
  - data/ - 数据解析和处理
  - item/ - 物品翻译处理
  - stat/ - 词缀翻译处理
  - util/ - 工具类
- data_repo/ - 游戏数据导出和存储
- Awakened-PoE-Trade-Simplified-Chinese/ - APT简体中文版
- poe-dat-viewer/ - 游戏数据导出工具

## 使用步骤

1. 前置要求: 请确保已经安装了 nodejs 且版本 >= 18
2. 使用 `git clone --recurse-submodules <url>` clone 当前仓库; 因为该仓库使用 submodule 关联了 [poe-dat-viewer(魔改版, 支持简中)](https://github.com/DonkiChen/poe-dat-viewer)
3. 确认 [intl-config.json](./data_repo/exported/intl/config.json) 与 [tencent-config.json](./data_repo/exported/tencent/config.json) 中游戏文件夹的路径正确(第二行的 `steam`)
4. 执行 `(cd ./data_repo && sh export.sh)`, 会编译 poe-dat-viewer 并导出游戏文件
5. 运行 [Main.kt](./src/main/kotlin/Main.kt) 会在 APT 目录下生成翻译好的 items.ndjson 和 stats.ndjson 文件

## 注意事项

- 目前仅支持: 国服**原版**数据 或 国际服 A 大**汉化补丁**后的数据, 可以通过 [usePatchedIntlStatDescriptionFiles](./src/main/kotlin/Config.kt) 控制

## 致谢

本项目基于以下优秀项目:
- [poe-dat-viewer(by Snosme)](https://github.com/SnosMe/poe-dat-viewer)