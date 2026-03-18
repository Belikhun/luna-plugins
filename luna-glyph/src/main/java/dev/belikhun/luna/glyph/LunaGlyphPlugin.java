package dev.belikhun.luna.glyph;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.velocity.LunaCoreVelocity;
import dev.belikhun.luna.glyph.command.LunaGlyphAdminCommand;
import dev.belikhun.luna.glyph.config.GlyphConfigService;
import dev.belikhun.luna.glyph.config.GlyphPackConfig;
import dev.belikhun.luna.glyph.config.GlyphPluginState;
import dev.belikhun.luna.glyph.placeholder.GlyphMiniPlaceholders;
import dev.belikhun.luna.glyph.placeholder.GlyphTabPlaceholders;
import dev.belikhun.luna.glyph.service.GlyphPackBuilder;
import dev.belikhun.luna.pack.api.LunaPackApi;
import dev.belikhun.luna.pack.api.LunaPackDynamicContext;
import dev.belikhun.luna.pack.api.LunaPackRegistration;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

@Plugin(
	id = "lunaglyph",
	name = "LunaGlyph",
	version = BuildConstants.VERSION,
	description = "Glyph resource-pack generator and placeholder bridge",
	dependencies = {
		@Dependency(id = "lunacore"),
		@Dependency(id = "lunapackloader", optional = false),
		@Dependency(id = "miniplaceholders", optional = true),
		@Dependency(id = "tab", optional = true)
	},
	authors = {"Belikhun"}
)
public final class LunaGlyphPlugin {
	private static final String DYNAMIC_PROVIDER_ID = "lunaglyph";

	private final ProxyServer server;
	private final LunaLogger logger;
	private final Path dataDirectory;
	private final GlyphConfigService configService;
	private final GlyphPackBuilder packBuilder;

	private GlyphPluginState state;
	private LunaPackApi lunaPackApi;
	private GlyphMiniPlaceholders miniPlaceholders;
	private GlyphTabPlaceholders tabPlaceholders;
	private volatile Consumer<String> reloadReporter;

	@Inject
	public LunaGlyphPlugin(ProxyServer server, @DataDirectory Path dataDirectory) {
		this.server = server;
		this.logger = LunaLogger.forLogger(Logger.getLogger("LunaGlyph"), true).scope("Glyph");
		this.dataDirectory = dataDirectory;
		this.configService = new GlyphConfigService(dataDirectory, logger);
		this.packBuilder = new GlyphPackBuilder(logger);
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		configService.ensureDefaults();
		state = configService.loadState();

		registerCommand();
		resolveLunaPackApi();
		reloadDynamicPackRegistration();
		registerMiniPlaceholders();
		registerTabPlaceholders();

		logger.success("LunaGlyph đã khởi động.");
	}

	private void registerCommand() {
		CommandManager commandManager = server.getCommandManager();
		CommandMeta meta = commandManager.metaBuilder("lunaglyph")
			.aliases("glyph")
			.plugin(this)
			.build();

		commandManager.register(meta, new LunaGlyphAdminCommand(this::reloadAll));
	}

	private void resolveLunaPackApi() {
		lunaPackApi = LunaCoreVelocity.services().dependencyManager().resolve(LunaPackApi.class);
		logger.audit("Đã resolve LunaPackApi thành công.");
	}

	private synchronized void reloadAll(Consumer<String> reporter) {
		reloadReporter = reporter;
		report("Bắt đầu nạp lại cấu hình LunaGlyph.");
		state = configService.loadState();
		report("Đã nạp " + state.glyphs().size() + " glyph từ cấu hình.");
		reloadDynamicPackRegistration();
		if (miniPlaceholders != null) {
			report("Đang đăng ký lại MiniPlaceholders.");
			miniPlaceholders.register(state.placeholderValues());
			report("Đã đăng ký lại MiniPlaceholders thành công.");
		}
		if (tabPlaceholders != null) {
			report("Đang đăng ký lại TAB placeholders.");
			tabPlaceholders.register(state.placeholderValues());
			report("Đã đăng ký lại TAB placeholders thành công.");
		}
		report("Hoàn tất reload LunaGlyph.");
		reloadReporter = null;
	}

	private void reloadDynamicPackRegistration() {
		if (lunaPackApi == null) {
			report("Bỏ qua sync LunaPack vì LunaPackApi chưa sẵn sàng.");
			return;
		}

		report("Đang đăng ký dynamic provider vào LunaPack.");
		lunaPackApi.unregisterDynamicProvider(DYNAMIC_PROVIDER_ID);
		lunaPackApi.registerDynamicProvider(DYNAMIC_PROVIDER_ID, this::provideDynamicPack);
		var report = lunaPackApi.reload();
		report("LunaPack reload xong: khả dụng " + report.resolvedAvailable() + "/" + report.validDefinitions() + ", thiếu file " + report.resolvedMissingFiles() + ".");
		logger.audit("Đã sync LunaPack sau cập nhật glyph: " + report.resolvedAvailable() + "/" + report.validDefinitions());
	}

	private List<LunaPackRegistration> provideDynamicPack(LunaPackDynamicContext context) {
		GlyphPluginState currentState = state;
		if (currentState == null || currentState.glyphs().isEmpty()) {
			report("Không có glyph nào để build pack động.");
			return List.of();
		}

		Path outputZip = context.packPath().resolve(currentState.pack().filename()).normalize();
		Path iconPath = dataDirectory.resolve("icon.png");
		Path glyphsDirectory = dataDirectory.resolve("glyphs");
		report("Đang build pack glyph tại: " + outputZip);

		var result = packBuilder.build(currentState, outputZip, iconPath, glyphsDirectory);
		if (!result.success()) {
			report("Build pack thất bại: " + result.errorMessage());
			logger.warn("Build glyph pack thất bại: " + result.errorMessage());
			return List.of();
		}
		report("Build pack thành công: " + result.generatedGlyphs() + " glyph.");

		GlyphPackConfig pack = currentState.pack();
		report("Đang tạo đăng ký pack động: name=" + pack.name() + ", file=" + pack.filename() + ", priority=" + pack.priority() + ".");
		return List.of(new LunaPackRegistration(
			pack.name(),
			pack.filename(),
			pack.priority(),
			pack.required(),
			pack.enabled(),
			pack.servers()
		));
	}

	private void report(String line) {
		logger.audit(line);
		Consumer<String> reporter = reloadReporter;
		if (reporter != null) {
			reporter.accept(line);
		}
	}

	private void registerMiniPlaceholders() {
		if (server.getPluginManager().getPlugin("miniplaceholders").isEmpty()) {
			return;
		}

		try {
			miniPlaceholders = new GlyphMiniPlaceholders(logger);
			miniPlaceholders.register(state.placeholderValues());
		} catch (Throwable throwable) {
			logger.error("Không thể đăng ký MiniPlaceholders cho LunaGlyph.", throwable);
			miniPlaceholders = null;
		}
	}

	private void registerTabPlaceholders() {
		if (server.getPluginManager().getPlugin("tab").isEmpty()) {
			return;
		}

		try {
			tabPlaceholders = new GlyphTabPlaceholders(logger);
			tabPlaceholders.register(state.placeholderValues());
		} catch (Throwable throwable) {
			logger.error("Không thể đăng ký TAB placeholders cho LunaGlyph.", throwable);
			tabPlaceholders = null;
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		if (miniPlaceholders != null) {
			miniPlaceholders.unregister();
		}
		if (tabPlaceholders != null) {
			tabPlaceholders.unregister();
		}
		if (lunaPackApi != null) {
			lunaPackApi.unregisterDynamicProvider(DYNAMIC_PROVIDER_ID);
		}
	}
}
