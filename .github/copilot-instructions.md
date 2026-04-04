# Copilot Instructions for luna-plugins

## Project shape
- This is a Gradle multi-project workspace (`settings.gradle.kts`) with shared API + platform modules (`luna-*`).
- Current modules:
  - `:luna-core-api` → shared cross-platform APIs/utilities (library module, not a runtime plugin jar).
  - `:luna-core-paper` → Paper runtime/plugin implementation for Luna Core.
  - `:luna-core-velocity` → Velocity target for Luna Core.
  - `:luna-vault-api` → shared economy contracts, repositories, RPC payloads, and money types.
  - `:luna-vault` → Velocity-side economy authority / source of truth for the network.
  - `:luna-vault-backend` → Paper-side Vault bridge and backend sync adapter.
  - `:luna-pack` → Velocity plugin for server resource-pack management/loading.
  - `:luna-glyph` → Velocity glyph resource-pack generator and placeholder bridge; integrates with `:luna-pack`.
  - `:luna-shop` → shop feature plugin (depends on `:luna-core-api` + `:luna-core-paper`).
  - `:luna-countdown` → countdown feature plugin (depends on `:luna-core-api` + `:luna-core-paper`).
  - `:luna-hat` → hat/cosmetic feature plugin (depends on `:luna-core-api` + `:luna-core-paper`).
  - `:luna-smp` → SMP-focused feature plugin (depends on `:luna-core-api` + `:luna-core-paper`).
  - `:luna-messenger` → Paper-side messenger feature plugin (depends on `:luna-core-api` + `:luna-core-paper`).
  - `:luna-messenger-velocity` → Velocity-side messenger bridge plugin (depends on `:luna-core-api` + `:luna-core-velocity`).
  - `:luna-auth` → Velocity-side authentication authority.
  - `:luna-auth-backend` → Paper-side auth restriction and command-forwarding plugin.
  - `:luna-migrator` → Paper-side UUID/auth migration helper plugin.
- Current runtime split:
  - Velocity hosts network-wide authority and infra (`luna-auth`, `luna-vault`, `luna-pack`, `luna-glyph`, `luna-messenger-velocity`) on top of `:luna-core-velocity`.
  - Paper hosts shared backend runtime plus adapters and feature consumers (`luna-auth-backend`, `luna-vault-backend`, `luna-shop`, `luna-countdown`, `luna-hat`, `luna-smp`, `luna-messenger`, `luna-migrator`) on top of `:luna-core-paper`.
- The `luna-messenger-interactivechat/` folder currently exists in the workspace but is not included in `settings.gradle.kts`; treat it as non-participating unless explicitly added as a subproject.
- The `luna-auth-api/` and `luna-messenger-api/` folders may exist with build outputs, but they are not active subprojects in `settings.gradle.kts`.
- Keep shared contracts and reusable helpers in `luna-core-api`; keep platform runtime code in `luna-core-paper` / `luna-core-velocity`.
- Prefer adding new plugins as sibling subprojects (`luna-*`) and include them in `settings.gradle.kts`.

## Plugin metadata conventions
- Paper modules use `paper-plugin.yml` under `src/main/resources`.
- Velocity modules use Velocity metadata generation via `@Plugin` (from `velocity-api` annotation processor).
- Plugin names must be valid identifiers (no spaces), e.g. `LunaCore`, `LunaShop`.
- If a module depends on another plugin at runtime, declare it in `paper-plugin.yml` under `dependencies.server`.
- For runtime-downloaded libraries, prefer Paper `loader` + `PluginLoader` (`MavenLibraryResolver`) instead of shading heavy dependencies into the plugin jar.
- Keep `paper-plugin.yml` `loader:` aligned with actual loader class path when used.

## Dependency wiring (must stay aligned)
- Build-time linkage is in module Gradle files:
  - `luna-vault-api/build.gradle.kts` uses `compileOnly(project(":luna-core-api"))`.
  - `luna-vault/build.gradle.kts` uses `implementation(project(":luna-vault-api"))` and `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-velocity"))`.
  - `luna-vault-backend/build.gradle.kts` uses `implementation(project(":luna-vault-api"))` and `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-paper"))`.
  - `luna-auth/build.gradle.kts` uses `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-velocity"))`.
  - `luna-auth-backend/build.gradle.kts` uses `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-paper"))`.
  - `luna-migrator/build.gradle.kts` uses `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-paper"))`.
  - `luna-shop/build.gradle.kts` uses `compileOnly(project(":luna-core-api"))` and `compileOnly(project(":luna-core-paper"))`.
  - `luna-countdown`, `luna-hat`, `luna-smp`, and `luna-messenger` follow the same `:luna-core-api` + `:luna-core-paper` linkage.
  - `luna-pack` and `luna-messenger-velocity` use `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-velocity"))`.
  - `luna-glyph/build.gradle.kts` uses `compileOnly(project(":luna-core-api"))` + `compileOnly(project(":luna-core-velocity"))` + `compileOnly(project(":luna-pack"))`.
- Runtime/plugin loading linkage is in descriptor files:
  - All active Paper-side plugins (`luna-shop`, `luna-countdown`, `luna-hat`, `luna-smp`, `luna-messenger`, `luna-auth-backend`, `luna-vault-backend`, `luna-migrator`) must stay aligned with their `paper-plugin.yml` runtime dependencies.
  - Current Paper-side feature/adaptor plugins depend on `LunaCore` at runtime through `dependencies.server` in `paper-plugin.yml`.
  - `luna-vault-backend` also declares runtime integration with Vault and optional HuskHomes behavior.
  - Velocity modules express runtime plugin dependencies through `@Plugin` annotations, not `paper-plugin.yml`.
- When adding new inter-plugin dependencies, update both Gradle and `paper-plugin.yml`.
- For external libraries resolved by Paper loader:
  - keep module dependencies as `compileOnly` for compile-time symbols.
  - register runtime Maven coordinates in loader class (not shaded into output jar).

## Formatting
- Java/Gradle/Groovy: **tab indent** (tab width=4).
- YAML: **spaces only** (see `.editorconfig`).

## GUI UX rules
- **Text Formatting Rule v2**
  - **Readability baseline**:
    - Use scannable text with logical blank lines between sections.
    - On dark GUI backgrounds, avoid dark/low-contrast text colors.
    - In item names/lore, each line must contain at most **7 words**.
  - **Color and gradient tiering**:
    - **Low tier**: basic solid colors only (no gradient).
    - **Mid tier**: more eye-catching solid colors.
    - **High tier**: simple/basic gradients.
    - **Higher tier**: richer, more colorful gradients.
    - Keep gradients for headings/highlights; body lines should remain stable and readable.
  - **Inline value highlight**:
    - Color/gradient may be used inside a sentence to emphasize key values (stats, prices, multipliers), similar to game-style stat highlighting.
    - Prefer solid color first; use short gradient only for truly critical values.
    - Keep highlight density low; do not color every value in one sentence.
  - **Text styles**:
    - Bold: for key values and critical labels.
    - Italic: only for minor notes; use sparingly.
    - Underline: only for important action keywords.
    - Strike: only for unavailable/invalid/overridden content.
  - **Lore marker and icons**:
    - Use `▍` only for multi-line information blocks.
    - Do not use `▍` on single standalone/non-information lines.
    - Use only Minecraft-safe symbols from the allowed icon list.
  - **Structure patterns**:
    - **Leveled indentation**: keep hierarchy shallow (max 2 levels in lore).
    - **List usage**: use list style for grouped items/actions with consistent phrasing.
    - **Textblock usage**: use short multi-line blocks for grouped info (2–4 lines), with clear start and end.
- **Surface-based color usage**:
  - **Chat and scoreboard** (dark background):
    - Prefer bright/high-contrast colors for important text.
    - Use mid colors only for less important/supporting information.
    - Avoid dark/low-contrast colors.
  - **Inventory title (GUI name)** (white/light inventory background):
    - Prefer dark colors for strong readability.
    - Keep title contrast high and avoid overly bright washed-out colors.
  - **Action bar, boss bar, title/subtitle** (rendered over world scene):
    - Use neutral or bright colors for robust readability across day/night and mixed world lighting.
    - Avoid very dark colors that can disappear on dark scenes.
- **Core palette usage (`LunaPalette`)**:
  - Use palette tokens from `luna-core-api/src/main/java/dev/belikhun/luna/core/api/ui/LunaPalette.java`; avoid hard-coded hex in feature modules.
  - Shade intent:
    - Bright: `*_100`, `*_300` (high-contrast text on dark surfaces).
    - Mid: `*_500` (default accent and actionable text).
    - Dark: `*_700`, `*_900` (titles/labels on light surfaces).
  - Use semantic families by meaning:
    - Success: `SUCCESS_*`
    - Warning: `WARNING_*` or `AMBER_*`
    - Error: `DANGER_*`
    - Info: `INFO_*` or `SKY_*`
  - For gradients, compose from palette tokens of adjacent shades/families instead of arbitrary colors.

## Language/localization
- All player-facing text **must be Vietnamese** with full accents (chat, GUI, HUD, templates).
- Text must look professional and consistent; formatting/icons are allowed.
- Use helpers: `Text.msg`, `Text.item`, `Text.title`, MiniMessage in `config.yml`.
- Console logs may be English; player-facing text must not.
- Legacy `&` formatting is available (`Text.msgLegacy`) but discouraged.

## UI icons
- **Minecraft-safe symbols only** (do not use lookalikes).
- Bullet: `●` | Dot: `⬤` | Check: `✔` | Cross: `❌` | Info: `ℹ` | Time: `⌚` | Wait: `⌛ ⏳`.
- Allowed symbols:
  - `🪓` `⛏` `⚔` `🗡` `🏹` `⬤` `█` `»` `▶` `★` `❤` `•` `☀` `⛀⛁💰` `☠` `☹` `👾` `👻` `🔧` `🌧` `☁` `☄` `⭐` `🔍` `☮` `☯` `⛔` `⚠` `🔔` `♣` `♦` `♥` `♠` `🔥` `❣` `💢💬` `💤` `☹` `☻` `☺` `☂` `☃` `™` `®` `©` `₪` `★` `☆` `✦` `🧪` `⚗`.
  - `≈⌀⌂【】⊻⊼⊽⋃↔↑↓→←◎☽ⒶⒷⒸⒹⒺⒻⒼⒽⒾⒿⓀⓁⓂⓃⓄⓅⓆⓇⓈⓉⓊⓋⓌⓍⓎⓏⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ∃∧∨∩⊂⊃∀ΞΓɐəɘεβɟɥɯɔиɹʁяʌʍλчΣΠηαʊїз¢№¿¡ƸӜƷξЖ`.
  - All block drawing unicode characters.

## Build and toolchain
- Java 21 is required; root build enforces toolchain and `--release 21` (`build.gradle.kts`).
- Paper API dependency comes from version catalog (`gradle/libs.versions.toml`): `libs.paper.api`.
- Velocity API, RabbitMQ, JDA, MiniPlaceholders, TAB, Vault, PlaceholderAPI, LuckPerms, InteractiveChat, HuskHomes, and Spark versions are also centralized in `gradle/libs.versions.toml`.
- Repositories are centralized in root `build.gradle.kts` (Maven Central, Paper repo, Lucko repo, HelpChat repo, LoohpJames repo, William278 repo, JitPack).
- Gradle performance flags are enabled (`gradle.properties`): configuration cache, parallel, build cache.

## Build/Run
- Build with `./gradlew build`.
- Always run `./gradlew shadowJar` at the end of your work to verify build errors.
- Shadow outputs are centralized in root `output/`.
- Shadow artifact naming convention: `<module>-<platform>-all.jar` (no version segment).
- Current platforms: `paper`, `velocity`.
- API-only modules (`:luna-core-api`, `:luna-vault-api`) are intentionally excluded from `shadowJar` in root build conventions.
- `clean` must also remove root `output/` directory.
- JUnit is configured globally in the root build, but coverage is partial and not every module currently has meaningful tests.

## Common workflows
- Build all plugins: `./gradlew build` (Windows: `./gradlew.bat build`).
- Build one module: `./gradlew :luna-core-paper:build` (same form for any module).
- Clean + rebuild after descriptor/dependency changes: `./gradlew clean build`.
- Build distributable jars: `./gradlew shadowJar` (Windows: `./gradlew.bat shadowJar`).
- When editing docs or repo guidance, treat `settings.gradle.kts` and root `build.gradle.kts` as the source of truth for active modules and packaging behavior.

## Code patterns to keep
- Paper entrypoints extend `JavaPlugin`; Velocity entrypoints use `@Plugin` + constructor injection.
- Entry points are no longer intentionally minimal; they commonly orchestrate bootstrap, config, service registration, command binding, placeholder registration, and shutdown cleanup.
- Keep orchestration in entrypoints readable, but move sustained business logic into dedicated services/gateways/controllers rather than letting `onEnable` become a dumping ground.
- Current representative entrypoints:
  - `luna-core-paper/src/main/java/dev/belikhun/luna/core/paper/LunaCorePlugin.java`
  - `luna-core-velocity/src/main/java/dev/belikhun/luna/core/velocity/LunaCoreVelocityPlugin.java`
  - `luna-auth/src/main/java/dev/belikhun/luna/auth/LunaAuthVelocityPlugin.java`
  - `luna-auth-backend/src/main/java/dev/belikhun/luna/auth/backend/LunaAuthBackendPlugin.java`
  - `luna-vault/src/main/java/dev/belikhun/luna/vault/LunaVaultVelocityPlugin.java`
  - `luna-vault-backend/src/main/java/dev/belikhun/luna/vault/backend/LunaVaultBackendPlugin.java`
  - `luna-pack/src/main/java/dev/belikhun/luna/pack/LunaPackLoaderPlugin.java`
  - `luna-glyph/src/main/java/dev/belikhun/luna/glyph/LunaGlyphPlugin.java`
  - `luna-messenger/src/main/java/dev/belikhun/luna/messenger/paper/LunaMessengerPaperPlugin.java`
  - `luna-messenger-velocity/src/main/java/dev/belikhun/luna/messenger/velocity/LunaMessengerVelocityPlugin.java`
- Keep Paper loader logic in `luna-core-paper/src/main/java/dev/belikhun/luna/core/paper/loader/LunaCoreLibraryLoader.java` for dynamic JDBC library resolution.
- Current supported DB drivers in `luna-core-paper`: `sqlite`, `mysql`, `mariadb`.
- Keep Velocity bootstrap entry in `luna-core-velocity/src/main/java/dev/belikhun/luna/core/velocity/LunaCoreVelocityPlugin.java`.
- Keep Velocity version constants generated from templates:
  - `luna-core-velocity/src/main/templates/dev/belikhun/luna/core/velocity/BuildConstants.java.tpl`
  - `luna-pack/src/main/templates/dev/belikhun/luna/pack/BuildConstants.java.tpl`
  - `luna-auth/src/main/templates/dev/belikhun/luna/auth/BuildConstants.java.tpl`
  - `luna-vault/src/main/templates/dev/belikhun/luna/vault/BuildConstants.java.tpl`
  - `luna-glyph/src/main/templates/dev/belikhun/luna/glyph/BuildConstants.java.tpl`
  - `luna-messenger-velocity/src/main/templates/dev/belikhun/luna/messenger/velocity/BuildConstants.java.tpl`
- Keep root-level shared Gradle behavior in `build.gradle.kts`; keep module behavior minimal and focused on dependencies.
- Respect the current authority split when moving code:
  - network-wide auth logic belongs in `luna-auth` on Velocity.
  - network-wide economy authority belongs in `luna-vault` on Velocity.
  - Paper-side auth/economy modules are adapters, enforcement layers, or backend-local integrations, not competing sources of truth.

## Reuse and deduplication rules
- Avoid duplicated code across modules. Before adding a new helper, check whether a similar method already exists.
- If logic is used (or expected to be used) by 2+ plugins/modules, move it into `luna-core-api` and reuse it from feature modules/platform modules.
- Keep feature modules (e.g. `luna-shop`) focused on feature/business flows; shared concerns must live in `luna-core-api` (text formatting, lore wrapping, pagination, command completion, GUI helpers, common validators).
- Prefer extending existing core utility classes before creating new feature-local helpers with overlapping behavior.
- During refactors, replace duplicate call-sites with core utilities instead of maintaining parallel implementations.
