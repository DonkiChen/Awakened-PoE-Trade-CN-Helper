# Repository Guidelines

## Project Structure & Module Organization
The root project is a Kotlin/JVM tool that generates translated `items.ndjson` and `stats.ndjson` for Awakened PoE Trade. Main code lives in `src/main/kotlin`, split by concern: `data/` for parsing and repository access, `item/` and `stat/` for patching, and `util/` for shared helpers. Runtime export inputs live in `data_repo/`; local source paths are configured in `data_repo/exported/*/config.json`. `poe-dat-viewer/` is a submodule used to export game data, with `lib/` for the CLI library and `viewer/` for the Vue UI. Generated output in `build/`, `bin/`, and `data_repo/exported/*/{files,tables}` is not source.

## Build, Test, and Development Commands
Use `.\gradlew.bat build` to compile the Kotlin project and run all configured checks. Use `.\gradlew.bat test` for root JVM tests only. Run `cd data_repo && sh export.sh` to rebuild `poe-dat-viewer/lib` and export patch data before running `src/main/kotlin/Main.kt`. For the viewer, use `cd poe-dat-viewer/viewer && npm install`, then `npm run dev` for local development, `npm run lint` for ESLint, and `npm run build` for lint, type-checking, and production output. If you clone fresh, initialize submodules first with `git submodule update --init --recursive`.

## Coding Style & Naming Conventions
Follow existing Kotlin style: 4-space indentation, `PascalCase` for objects and classes, `camelCase` for functions and properties, and package-based organization by feature. Keep parser and patcher logic in their existing folders instead of introducing broad utility files at the root. In `poe-dat-viewer/viewer`, Vue components use `PascalCase` filenames such as `Workbench.vue`; utility modules use descriptive lowercase names. ESLint is configured in `poe-dat-viewer/viewer/eslint.config.js`; match current formatting instead of adding a separate formatter.

## Testing Guidelines
The root build uses Kotlin `test` with JUnit Platform, but there is currently no populated `src/test/kotlin` tree. Add focused unit tests there when changing parsing or patch logic. The viewer has no dedicated test runner configured, so frontend changes should at minimum pass `npm run lint` and `npm run build`. There is no enforced coverage threshold; rely on targeted tests plus sample export verification.

## Commit & Pull Request Guidelines
Recent commits use short, imperative subjects in either Chinese or English, for example `add support for imbued gems`. Keep commit messages brief, scoped to one change, and avoid mixing refactors with data updates. Pull requests should describe the affected data source or module, list any edited config files, and include sample output or screenshots when `poe-dat-viewer/viewer` changes. Call out machine-specific path changes in `data_repo/exported/*/config.json` so reviewers can verify they are intentional.
