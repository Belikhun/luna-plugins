package dev.belikhun.luna.pack.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.pack.config.LoaderConfigService;
import dev.belikhun.luna.pack.config.PackRepository;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackReloadReport;
import dev.belikhun.luna.pack.model.PlayerPackSession;
import dev.belikhun.luna.pack.model.ResolvedPack;
import dev.belikhun.luna.pack.service.BuiltInPackHttpService;
import dev.belikhun.luna.pack.service.PackCatalogService;
import dev.belikhun.luna.pack.service.PackDispatchService;
import dev.belikhun.luna.pack.service.PlayerPackSessionStore;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PackAdminCommand implements SimpleCommand {
	private static final String ROOT_PERMISSION = "lunapack.admin";
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final int MAX_INPUT_LENGTH = 64;

	private final ProxyServer server;
	private final LoaderConfigService configService;
	private final PackCatalogService catalogService;
	private final BuiltInPackHttpService builtInHttpService;
	private final PlayerPackSessionStore sessionStore;
	private final PackDispatchService dispatchService;

	public PackAdminCommand(
		ProxyServer server,
		LoaderConfigService configService,
		PackCatalogService catalogService,
		BuiltInPackHttpService builtInHttpService,
		PlayerPackSessionStore sessionStore,
		PackDispatchService dispatchService
	) {
		this.server = server;
		this.configService = configService;
		this.catalogService = catalogService;
		this.builtInHttpService = builtInHttpService;
		this.sessionStore = sessionStore;
		this.dispatchService = dispatchService;
	}

	@Override
	public void execute(Invocation invocation) {
		handle(invocation.source(), invocation.arguments());
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		Collection<String> values = suggest(invocation.source(), invocation.arguments());
		return values.stream().toList();
	}

	private void handle(CommandSource source, String[] args) {
		if (args.length == 0) {
			send(source, "<yellow>ℹ Dùng /lunapack <reload|resend|forceload|forceunload|template|enable|disable|debug>.</yellow>");
			return;
		}

		String sub = sanitizeArg(args[0]);
		if (sub == null) {
			send(source, "<red>❌ Tham số lệnh không hợp lệ hoặc quá dài.</red>");
			return;
		}

		sub = sub.toLowerCase(Locale.ROOT);
		switch (sub) {
			case "reload" -> handleReload(source);
			case "resend" -> handleResend(source, args);
			case "forceload" -> handleForceLoad(source, args);
			case "forceunload" -> handleForceUnload(source, args);
			case "template" -> handleTemplate(source, args);
			case "enable" -> handleSetEnabled(source, args, true);
			case "disable" -> handleSetEnabled(source, args, false);
			case "debug" -> handleDebug(source, args);
			default -> send(source, CommandStrings.usage("/lunapack", CommandStrings.required("reload|resend|forceload|forceunload|template|enable|disable|debug", "action")));
		}
	}

	private Collection<String> suggest(CommandSource source, String[] args) {
		if (!hasPermission(source, ROOT_PERMISSION)) {
			return List.of();
		}

		List<String> root = List.of("reload", "resend", "forceload", "forceunload", "template", "enable", "disable", "debug");
		if (args.length <= 1) {
			String input = args.length == 0 ? "" : args[0];
			return CommandCompletions.filterPrefix(root, input);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("resend")) {
			List<String> names = server.getAllPlayers().stream().map(Player::getUsername).toList();
			return CommandCompletions.filterPrefix(names, args[1]);
		}

		if (args.length == 2 && (args[0].equalsIgnoreCase("forceload") || args[0].equalsIgnoreCase("forceunload"))) {
			List<String> names = server.getAllPlayers().stream().map(Player::getUsername).toList();
			return CommandCompletions.filterPrefix(names, args[1]);
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
			List<String> names = server.getAllPlayers().stream().map(Player::getUsername).toList();
			return CommandCompletions.filterPrefix(Stream.concat(Stream.of("state", "on", "off", "toggle"), names.stream()).toList(), args[1]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("forceload")) {
			List<String> packs = catalogService.snapshot().definitionsByName().values().stream()
				.map(pack -> pack.name())
				.toList();
			return CommandCompletions.filterPrefix(packs, args[2]);
		}

		if (args.length == 2 && (args[0].equalsIgnoreCase("template") || args[0].equalsIgnoreCase("enable") || args[0].equalsIgnoreCase("disable"))) {
			List<String> packs = catalogService.snapshot().definitionsByName().values().stream()
				.map(pack -> pack.name())
				.toList();
			return CommandCompletions.filterPrefix(packs, args[1]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("forceunload")) {
			Player target = server.getPlayer(args[1]).orElse(null);
			if (target == null) {
				return List.of();
			}
			PlayerPackSession session = sessionStore.get(target.getUniqueId());
			if (session == null) {
				return List.of();
			}
			List<String> loaded = session.loadedByName().keySet().stream().toList();
			return CommandCompletions.filterPrefix(loaded, args[2]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("state")) {
			List<String> names = server.getAllPlayers().stream().map(Player::getUsername).toList();
			return CommandCompletions.filterPrefix(names, args[2]);
		}

		if (args.length == 3 && args[0].equalsIgnoreCase("debug") && !args[1].equalsIgnoreCase("state")) {
			return CommandCompletions.filterPrefix(List.of("on", "off", "toggle"), args[2]);
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("state")) {
			return List.of();
		}

		if (args.length == 4 && args[0].equalsIgnoreCase("debug")) {
			return CommandCompletions.filterPrefix(List.of("on", "off", "toggle"), args[3]);
		}

		return List.of();
	}

	private void handleReload(CommandSource source) {
		if (!hasPermission(source, "lunapack.admin.reload")) {
			sendNoPermission(source);
			return;
		}

		var config = builtInHttpService.resolve(configService.load());
		PackReloadReport report = catalogService.reload(config);
		send(source, "<green>✔ Đã tải lại cấu hình và dữ liệu pack.</green>");
		send(source, "<gray>• File phát hiện: <white>" + report.discoveredFiles() + "</white></gray>");
		send(source, "<gray>• Pack hợp lệ: <white>" + report.validDefinitions() + "</white> | lỗi: <white>" + report.invalidDefinitions() + "</white></gray>");
		send(source, "<gray>• Pack khả dụng: <white>" + report.resolvedAvailable() + "</white> | thiếu file: <white>" + report.resolvedMissingFiles() + "</white> | URL lỗi: <white>" + report.resolvedInvalidUrls() + "</white></gray>");
	}

	private void handleTemplate(CommandSource source, String[] args) {
		if (!hasPermission(source, "lunapack.admin.template")) {
			sendNoPermission(source);
			return;
		}

		if (args.length < 2) {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("template"), CommandStrings.required("packName", "text")));
			return;
		}

		String packName = sanitizeArg(args[1]);
		if (packName == null) {
			send(source, "<red>❌ Tên pack không hợp lệ.</red>");
			return;
		}

		PackRepository.TemplateCreateResult result = catalogService.createTemplate(packName);
		if (result.isInvalidName()) {
			send(source, "<red>❌ Tên pack không hợp lệ. Chỉ dùng <white>a-z, 0-9, _, -</white>.</red>");
			return;
		}

		if (result.isAlreadyExists()) {
			send(source, "<red>❌ Pack <white>" + packName + "</white> đã tồn tại.</red>");
			return;
		}

		if (result.isIoError() || !result.isCreated()) {
			send(source, "<red>❌ Không thể tạo template pack mới.</red>");
			return;
		}

		var config = builtInHttpService.resolve(configService.load());
		catalogService.reload(config);
		send(source, "<green>✔ Đã tạo template pack <white>" + packName.toLowerCase(Locale.ROOT) + "</white> tại <white>" + result.path().toAbsolutePath() + "</white>.</green>");
	}

	private void handleSetEnabled(CommandSource source, String[] args, boolean enabled) {
		String permission = enabled ? "lunapack.admin.enable" : "lunapack.admin.disable";
		if (!hasPermission(source, permission)) {
			sendNoPermission(source);
			return;
		}

		String commandName = enabled ? "enable" : "disable";
		if (args.length < 2) {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal(commandName), CommandStrings.required("pack", "text")));
			return;
		}

		String packName = sanitizeArg(args[1]);
		if (packName == null) {
			send(source, "<red>❌ Tên pack không hợp lệ.</red>");
			return;
		}

		PackRepository.ToggleResult result = catalogService.setEnabled(packName, enabled);
		if (result.isInvalidName()) {
			send(source, "<red>❌ Tên pack không hợp lệ. Chỉ dùng <white>a-z, 0-9, _, -</white>.</red>");
			return;
		}

		if (result.isNotFound()) {
			send(source, "<red>❌ Không tìm thấy pack <white>" + packName + "</white>.</red>");
			return;
		}

		if (result.isIoError()) {
			send(source, "<red>❌ Không thể cập nhật trạng thái pack <white>" + packName + "</white>.</red>");
			return;
		}

		var config = builtInHttpService.resolve(configService.load());
		catalogService.reload(config);

		if (result.oldEnabled() == result.newEnabled()) {
			String stateText = result.newEnabled() ? "bật" : "tắt";
			send(source, "<yellow>ℹ Pack <white>" + packName + "</white> đã ở trạng thái <white>" + stateText + "</white>.</yellow>");
			return;
		}

		String actionText = enabled ? "bật" : "tắt";
		send(source, "<green>✔ Đã " + actionText + " pack <white>" + packName + "</white>.</green>");
	}

	private void handleForceLoad(CommandSource source, String[] args) {
		if (!hasPermission(source, "lunapack.admin.forceload")) {
			sendNoPermission(source);
			return;
		}

		if (args.length < 3) {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("forceload"), CommandStrings.required("player", "text"), CommandStrings.required("pack", "text")));
			return;
		}

		String playerName = sanitizeArg(args[1]);
		String packName = sanitizeArg(args[2]);
		if (playerName == null || packName == null) {
			send(source, "<red>❌ Tên người chơi hoặc pack không hợp lệ.</red>");
			return;
		}

		Player target = server.getPlayer(playerName).orElse(null);
		if (target == null) {
			send(source, "<red>❌ Không tìm thấy người chơi <white>" + playerName + "</white>.</red>");
			return;
		}

		PackCatalogSnapshot snapshot = catalogService.snapshot();
		ResolvedPack pack = snapshot.findResolved(packName);
		if (pack == null) {
			send(source, "<red>❌ Không tìm thấy pack <white>" + packName + "</white>.</red>");
			return;
		}

		if (!pack.available()) {
			send(source, "<red>❌ Pack <white>" + pack.definition().name() + "</white> hiện không khả dụng: <white>" + pack.unavailableReason() + "</white>.</red>");
			return;
		}

		PlayerPackSession session = sessionStore.getOrCreate(target.getUniqueId());
		dispatchService.forceLoad(target, session, pack);
		send(source, "<green>✔ Đã forceload pack <white>" + pack.definition().name() + "</white> cho <white>" + target.getUsername() + "</white>.</green>");
	}

	private void handleForceUnload(CommandSource source, String[] args) {
		if (!hasPermission(source, "lunapack.admin.forceunload")) {
			sendNoPermission(source);
			return;
		}

		if (args.length < 3) {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("forceunload"), CommandStrings.required("player", "text"), CommandStrings.required("pack", "text")));
			return;
		}

		String playerName = sanitizeArg(args[1]);
		String packName = sanitizeArg(args[2]);
		if (playerName == null || packName == null) {
			send(source, "<red>❌ Tên người chơi hoặc pack không hợp lệ.</red>");
			return;
		}

		Player target = server.getPlayer(playerName).orElse(null);
		if (target == null) {
			send(source, "<red>❌ Không tìm thấy người chơi <white>" + playerName + "</white>.</red>");
			return;
		}

		PlayerPackSession session = sessionStore.getOrCreate(target.getUniqueId());
		boolean unloaded = dispatchService.forceUnload(target, session, packName);
		if (!unloaded) {
			send(source, "<red>❌ Người chơi <white>" + target.getUsername() + "</white> không có pack <white>" + packName + "</white> trong trạng thái đã tải.</red>");
			return;
		}

		send(source, "<green>✔ Đã forceunload pack <white>" + packName + "</white> cho <white>" + target.getUsername() + "</white>.</green>");
	}

	private void handleResend(CommandSource source, String[] args) {
		if (!hasPermission(source, "lunapack.admin.resend")) {
			sendNoPermission(source);
			return;
		}

		Player target;
		if (args.length >= 2) {
			String playerName = sanitizeArg(args[1]);
			if (playerName == null) {
				send(source, "<red>❌ Tên người chơi không hợp lệ.</red>");
				return;
			}
			target = server.getPlayer(playerName).orElse(null);
		} else if (source instanceof Player self) {
			target = self;
		} else {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("resend"), CommandStrings.required("player", "text")));
			return;
		}

		if (target == null) {
			send(source, "<red>❌ Không tìm thấy người chơi được chỉ định.</red>");
			return;
		}

		PlayerPackSession session = sessionStore.getOrCreate(target.getUniqueId());
		dispatchService.resend(target, session, catalogService.snapshot());
		send(source, "<green>✔ Đã gửi lại resource pack cho <white>" + target.getUsername() + "</white>.</green>");
	}

	private void handleDebug(CommandSource source, String[] args) {
		if (!hasPermission(source, "lunapack.admin.debug")) {
			sendNoPermission(source);
			return;
		}

		if (args.length == 1) {
			if (!(source instanceof Player self)) {
				send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("debug"), CommandStrings.required("player", "text"), CommandStrings.optional("on|off|toggle", "action")));
				return;
			}
			setDebug(source, self, "toggle");
			return;
		}

		if (args[1].equalsIgnoreCase("state")) {
			handleDebugState(source, args);
			return;
		}

		if (args.length == 2 && isDebugMode(args[1])) {
			if (!(source instanceof Player self)) {
				send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("debug"), CommandStrings.required("player", "text"), CommandStrings.optional("on|off|toggle", "action")));
				return;
			}
			setDebug(source, self, args[1]);
			return;
		}

		String playerName = sanitizeArg(args[1]);
		if (playerName == null) {
			send(source, "<red>❌ Tên người chơi không hợp lệ.</red>");
			return;
		}

		Player target = server.getPlayer(playerName).orElse(null);
		if (target == null) {
			send(source, "<red>❌ Không tìm thấy người chơi <white>" + playerName + "</white>.</red>");
			return;
		}

		String mode = args.length >= 3 ? sanitizeArg(args[2]) : "toggle";
		if (mode == null) {
			send(source, "<red>❌ Chế độ debug không hợp lệ.</red>");
			return;
		}

		if (!isDebugMode(mode)) {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("debug"), CommandStrings.required("player", "text"), CommandStrings.optional("on|off|toggle", "action")));
			return;
		}

		setDebug(source, target, mode);
	}

	private void handleDebugState(CommandSource source, String[] args) {
		if (!hasPermission(source, "lunapack.admin.debug.state")) {
			sendNoPermission(source);
			return;
		}

		Player target;
		if (args.length >= 3) {
			String playerName = sanitizeArg(args[2]);
			if (playerName == null) {
				send(source, "<red>❌ Tên người chơi không hợp lệ.</red>");
				return;
			}
			target = server.getPlayer(playerName).orElse(null);
		} else if (source instanceof Player self) {
			target = self;
		} else {
			send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("debug"), CommandStrings.literal("state"), CommandStrings.required("player", "text")));
			return;
		}

		if (target == null) {
			send(source, "<red>❌ Không tìm thấy người chơi được chỉ định.</red>");
			return;
		}

		PlayerPackSession session = sessionStore.getOrCreate(target.getUniqueId());
		String debug = session.debugEnabled() ? "<green>BẬT</green>" : "<red>TẮT</red>";
		String lastKnownServer = emptyAsDash(session.lastKnownServer());
		String previousServer = emptyAsDash(session.previousServer());
		String lastFailure = emptyAsDash(session.lastFailure());

		send(source, "<yellow>ℹ Trạng thái pack của <white>" + target.getUsername() + "</white>:</yellow>");
		send(source, "<gray>• Debug: </gray>" + debug);
		send(source, "<gray>• Server hiện tại: <white>" + lastKnownServer + "</white></gray>");
		send(source, "<gray>• Server trước đó: <white>" + previousServer + "</white></gray>");
		send(source, "<gray>• Loaded: <white>" + session.loadedByName().size() + "</white> | Pending: <white>" + session.pendingByPackId().size() + "</white></gray>");
		send(source, "<gray>• Lỗi gần nhất: <white>" + lastFailure + "</white></gray>");
	}

	private void setDebug(CommandSource source, Player target, String modeRaw) {
		String mode = modeRaw.toLowerCase(Locale.ROOT);
		PlayerPackSession session = sessionStore.getOrCreate(target.getUniqueId());
		switch (mode) {
			case "on" -> session.debugEnabled(true);
			case "off" -> session.debugEnabled(false);
			case "toggle" -> session.debugEnabled(!session.debugEnabled());
			default -> {
				send(source, CommandStrings.usage("/lunapack", CommandStrings.literal("debug"), CommandStrings.required("player", "text"), CommandStrings.optional("on|off|toggle", "action")));
				return;
			}
		}

		String state = session.debugEnabled() ? "<green>BẬT</green>" : "<red>TẮT</red>";
		send(source, "<green>✔ Đã cập nhật debug pack cho <white>" + target.getUsername() + "</white>: " + state + "</green>");
	}

	private boolean isDebugMode(String value) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
		return normalized.equals("on") || normalized.equals("off") || normalized.equals("toggle");
	}

	private String emptyAsDash(String value) {
		if (value == null || value.isBlank()) {
			return "-";
		}
		return value;
	}

	private String sanitizeArg(String value) {
		if (value == null) {
			return null;
		}

		String normalized = value.trim();
		if (normalized.isBlank() || normalized.length() > MAX_INPUT_LENGTH) {
			return null;
		}

		if (normalized.contains("\n") || normalized.contains("\r") || normalized.contains("\t") || normalized.contains("\0")) {
			return null;
		}

		return normalized;
	}

	private boolean hasPermission(CommandSource source, String permission) {
		if (!(source instanceof Player player)) {
			return true;
		}
		return player.hasPermission(permission);
	}

	private void sendNoPermission(CommandSource source) {
		send(source, "<red>❌ Bạn không có quyền dùng lệnh này.</red>");
	}

	private void send(CommandSource source, String text) {
		source.sendMessage(MM.deserialize(text));
	}
}
