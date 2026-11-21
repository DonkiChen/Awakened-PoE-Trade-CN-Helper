# Repository Guidelines

## Build and verification

- Run commands from the helper root: the generator resolves `data_repo/` locally and `../renderer/` in the parent checkout. The Gradle wrapper is 8.5, with a JVM 8 compilation toolchain.
- `.\gradlew.bat build` compiles and runs JUnit Platform tests in `src/test/kotlin`; it does not run the generator or require game exports. For a focused run: `.\gradlew.bat test --tests stat.TradeStatResolverTest`.
- `.\gradlew.bat generateTranslations` builds first, then runs `src/main/kotlin/Main.kt` using existing local exports. It does not export game data. `--args=--mod-names-only` writes only the marked `*_MODS` block; `--args=--check-mod-names` checks that block without writing it. For a read-only ID-vs-legacy stat audit: `.\gradlew.bat -q generateTranslations --args=--audit-stats --console=plain`. These modes still need exports.

## Export and translation inputs

- `.\scripts\build_and_export.bat` modifies and restores installed PoE files. Run a full export only when intended, with the game closed, local paths configured, and matching patch/restore ZIPs in `scripts/patch/`. `--check` publishes the patcher and checks the template exists and ZIPs pair up, but exits before building the exporter or touching game files.
- Both `PoePatcherCli/` (.NET 8 SDK) and `poe-dat-viewer/` are nested submodules. The export script builds `poe-dat-viewer/lib` (`npm install`, then `npx tsc`, Node ≥20); `viewer/` is a separate UI, not part of export.
- Add required tables and columns in `data_repo/exported/config.template.json` and re-export. Per-source `config.json`, `files/`, and `tables/` are ignored local outputs. A successful partial export is not necessarily enough: `Main.kt` selects four mappers, and `tencent_amsco2` uses English stat descriptions from `intl_amsco2`.
- For CSD directives, start in `data/parser/CsdGameDirectives.kt` under `src/main/kotlin`; directive stat indices are 1-based, template slots 0-based. Keep manual compatibility mappings in `data_repo/extra/`. See [README.md](README.md) for mapper language settings and audit record format.
