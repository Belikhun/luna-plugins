# Copilot Instructions for luna-plugins

## Project shape
- This is a Gradle multi-project workspace (`settings.gradle.kts`) and will contain multiple Paper plugin modules (`luna-*`).
- Current modules:
  - `:luna-core` → shared APIs/utilities for other Luna plugins.
  - `:luna-shop` → shop feature plugin that depends on `:luna-core`.
- Keep shared contracts and reusable helpers in `luna-core`; keep feature-specific code in `luna-shop`.
- Prefer adding new plugins as sibling subprojects (`luna-*`) and include them in `settings.gradle.kts`.

## Plugin metadata conventions
- Use `paper-plugin.yml` (not `plugin.yml`) in each module under `src/main/resources`.
- Current examples:
  - `luna-core/src/main/resources/paper-plugin.yml`
  - `luna-shop/src/main/resources/paper-plugin.yml`
- Plugin names must be valid identifiers (no spaces), e.g. `LunaCore`, `LunaShop`.
- If a module depends on another plugin at runtime, declare it in `paper-plugin.yml` under `dependencies.server`.

## Dependency wiring (must stay aligned)
- Build-time linkage is in module Gradle files:
  - `luna-shop/build.gradle.kts` uses `compileOnly(project(":luna-core"))`.
- Runtime/plugin loading linkage is in descriptor files:
  - `luna-shop` declares `dependencies.server.LunaCore` in `paper-plugin.yml`.
- When adding new inter-plugin dependencies, update both Gradle and `paper-plugin.yml`.

## Formatting
- Java/Gradle/Groovy: **tab indent** (tab width=4).
- YAML: **spaces only** (see `.editorconfig`).

## GUI UX rules
- **Scannable UI text**: add blank spacer lines between logical sections in GUI item lore.

## Language/localization
- All player-facing text **must be Vietnamese** with full accents (chat, GUI, HUD, templates).
- Text must look professional and consistent; formatting/icons are allowed.
- Use helpers: `Text.msg`, `Text.item`, `Text.title`, MiniMessage in `config.yml`.
- Console logs may be English; player-facing text must not.
- Legacy `&` formatting is available (`Text.msgLegacy`) but discouraged.

## UI icons
- **Minecraft-safe symbols only** (do not use lookalikes).
- Bullet: `●` | Check: `✔` | Cross: `❌` | Info: `ℹ` | Time: `⌚` | Wait: `⌛ ⏳`.
- Allowed symbols:
  - `🪓` `⛏` `⚔` `🗡` `🏹` `⬤` `█` `»` `▶` `★` `❤` `•` `☀` `⛀⛁💰` `☠` `☹` `👾` `👻` `🔧` `🌧` `☁` `☄` `⭐` `🔍` `☮` `☯` `⛔` `⚠` `🔔` `♣` `♦` `♥` `♠` `🔥` `❣` `💢💬` `💤` `☹` `☻` `☺` `☂` `☃` `™` `®` `©` `₪` `★` `☆` `✦` `🧪` `⚗`.
  - `≈⌀⌂【】⊻⊼⊽⋃↔↑↓→←◎☽ⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ∃∧∨∩⊂⊃∀ΞΓɐəɘεβɟɥɯɔиɹʁяʌʍλчΣΠηαʊїз¢№¿¡ƸӜƷξЖ`.
  - All block drawing unicode characters.

## Build and toolchain
- Java 21 is required; root build enforces toolchain and `--release 21` (`build.gradle.kts`).
- Paper API dependency comes from version catalog (`gradle/libs.versions.toml`): `libs.paper.api`.
- Repositories are centralized in root `build.gradle.kts` (Maven Central + Paper repo).
- Gradle performance flags are enabled (`gradle.properties`): configuration cache, parallel, build cache.

## Build/Run
- Build with `./gradlew build` (Paper API + Multiverse are `compileOnly`).
- Always run `./gradlew shadowJar` at the end of your work to verify build errors.
- No automated tests are defined yet.

## Common workflows
- Build all plugins: `./gradlew build` (Windows: `./gradlew.bat build`).
- Build one module: `./gradlew :luna-core:build` or `./gradlew :luna-shop:build`.
- Clean + rebuild after descriptor/dependency changes: `./gradlew clean build`.

## Code patterns to keep
- Main plugin entrypoints extend `JavaPlugin` and currently keep `onEnable/onDisable` minimal:
  - `luna-core/src/main/java/dev/belikhun/luna/core/LunaCorePlugin.java`
  - `luna-shop/src/main/java/dev/belikhun/luna/shop/LunaShopPlugin.java`
- Keep root-level shared Gradle behavior in `build.gradle.kts`; keep module behavior minimal and focused on dependencies.
