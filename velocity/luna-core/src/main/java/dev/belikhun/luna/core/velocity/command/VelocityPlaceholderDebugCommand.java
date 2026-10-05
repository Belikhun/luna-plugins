package dev.belikhun.luna.core.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.belikhun.luna.core.api.string.CommandStrings;
import dev.belikhun.luna.core.velocity.placeholder.LegacyPlaceholderIds;
import dev.belikhun.luna.core.velocity.placeholder.VelocityPlaceholderModule;
import dev.belikhun.luna.core.velocity.tabbridge.VelocityTabBridgeObserver;
import dev.belikhun.luna.core.velocity.tablist.TabListCapture;
import dev.belikhun.luna.core.velocity.tablist.VelocityTabListRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code placeholder} branch of the proxy admin command: inspection tools
 * that show what a player sees and what the bridge carried, without a client.
 *
 * Output lines are sent as plain components so they land in the proxy log
 * unchanged when driven from the console.
 */
public final class VelocityPlaceholderDebugCommand {
	private static final MiniMessage MM = MiniMessage.miniMessage();
	private static final int DEFAULT_RECENT = 20;
	private static final int MAX_RECENT = 64;

	private final ProxyServer proxyServer;
	private final VelocityTabListRenderer tabListRenderer;
	private final VelocityTabBridgeObserver bridgeObserver;
	private final VelocityPlaceholderModule module;

	public VelocityPlaceholderDebugCommand(
		ProxyServer proxyServer,
		VelocityTabListRenderer tabListRenderer,
		VelocityTabBridgeObserver bridgeObserver,
		VelocityPlaceholderModule module
	) {
		this.proxyServer = proxyServer;
		this.tabListRenderer = tabListRenderer;
		this.bridgeObserver = bridgeObserver;
		this.module = module;
	}

	/**
	 * Runs the sub-command held in {@code args}, which excludes the leading
	 * {@code placeholder} word.
	 */
	public void execute(CommandSource source, String[] args) {
		if (args.length == 0) {
			sendUsage(source);
			return;
		}

		String group = args[0].toLowerCase(Locale.ROOT);

		if (group.equals("tab")) {
			executeTab(source, args);
			return;
		}

		if (group.equals("bridge")) {
			executeBridge(source, args);
			return;
		}

		if (group.equals("list")) {
			sendLines(source, module.inspector().list(args.length > 1 ? args[1] : null));
			return;
		}

		if (group.equals("tabids")) {
			List<String> ids = module.tabIds();
			sendLines(source, List.of("── " + ids.size() + " TAB id(s) registered by luna ──"));
			sendLines(source, ids);
			return;
		}

		if (group.equals("parse")) {
			if (args.length < 2) {
				sendUsage(source);
				return;
			}

			sendLines(source, module.inspector().parse(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
			return;
		}

		if (group.equals("resolve")) {
			if (args.length < 3) {
				sendUsage(source);
				return;
			}

			Player target = args[1].equals("-") ? null : findPlayer(args[1]).orElse(null);

			if (target == null && !args[1].equals("-")) {
				source.sendRichMessage("<red>❌ Không tìm thấy người chơi đang online: <white>" + MM.escapeTags(args[1]) + "</white></red>");
				return;
			}

			String label = target == null ? "no player" : target.getUsername();
			sendLines(source, module.inspector().resolve(String.join(" ", Arrays.copyOfRange(args, 2, args.length)), target, label));
			return;
		}

		if (group.equals("render")) {
			if (args.length < 3) {
				sendUsage(source);
				return;
			}

			Player target = args[1].equals("-") ? null : findPlayer(args[1]).orElse(null);

			if (target == null && !args[1].equals("-")) {
				source.sendRichMessage("<red>❌ Không tìm thấy người chơi đang online: <white>" + MM.escapeTags(args[1]) + "</white></red>");
				return;
			}

			String template = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
			long started = System.nanoTime();
			String rendered = module.templates().render(template, target);
			sendLines(source, List.of(
				"── render for " + (target == null ? "no player" : target.getUsername()) + " · " + (System.nanoTime() - started) / 1000L + "µs ──",
				"  in : " + template,
				"  out: " + rendered
			));
			return;
		}

		if (group.equals("migrate")) {
			executeMigrate(source, args.length > 1 && args[1].equalsIgnoreCase("apply"));
			return;
		}

		sendUsage(source);
	}

	/**
	 * Rewrites pre-lunav ids in the proxy configs. A dry run prints every change;
	 * apply writes each file after saving a timestamped backup beside it.
	 */
	private void executeMigrate(CommandSource source, boolean apply) {
		int changedFiles = 0;
		int changedIds = 0;

		for (Path file : module.legacyScanTargets()) {
			if (!Files.isRegularFile(file)) {
				continue;
			}

			try {
				String text = Files.readString(file, StandardCharsets.UTF_8);
				Map<String, String> found = LegacyPlaceholderIds.findLegacy(text);

				if (found.isEmpty()) {
					continue;
				}

				changedFiles++;
				source.sendMessage(Component.text((apply ? "✔ " : "· ") + file.getParent().getFileName() + "/" + file.getFileName() + ": " + found.size() + " id"));

				for (Map.Entry<String, String> entry : found.entrySet()) {
					changedIds++;
					source.sendMessage(Component.text("    " + entry.getKey() + " → " + entry.getValue()));
				}

				if (apply) {
					Path backup = file.resolveSibling(file.getFileName() + ".bak-placeholders-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
					Files.copy(file, backup);
					Files.writeString(file, LegacyPlaceholderIds.rewrite(text), StandardCharsets.UTF_8);
				}
			} catch (IOException exception) {
				source.sendRichMessage("<red>❌ Không đọc/ghi được " + MM.escapeTags(file.toString()) + ": " + MM.escapeTags(String.valueOf(exception.getMessage())) + "</red>");
			}
		}

		if (changedFiles == 0) {
			source.sendRichMessage("<green>✔ Không còn placeholder cũ trong cấu hình proxy.</green>");
			return;
		}

		if (apply) {
			source.sendRichMessage("<green>✔ Đã ghi " + changedFiles + " file (" + changedIds + " id). Bản sao lưu nằm cạnh mỗi file; TAB cần /tab reload, messenger cần reload.</green>");
		} else {
			source.sendRichMessage("<yellow>ℹ Chưa ghi gì. Chạy <white>/luna placeholder migrate apply</white> để áp dụng.</yellow>");
		}
	}

	/**
	 * Tab completion for the same argument window.
	 */
	public List<String> suggest(String[] args) {
		if (args.length <= 1) {
			return filter(List.of("tab", "bridge", "parse", "resolve", "render", "list", "tabids", "migrate"), args.length == 0 ? "" : args[0]);
		}

		if ((args[0].equalsIgnoreCase("resolve") || args[0].equalsIgnoreCase("render")) && args.length == 2) {
			List<String> names = new ArrayList<>(List.of("-"));
			names.addAll(playerNames());
			return filter(names, args[1]);
		}

		if (args[0].equalsIgnoreCase("migrate") && args.length == 2) {
			return filter(List.of("dry-run", "apply"), args[1]);
		}

		String group = args[0].toLowerCase(Locale.ROOT);

		if (group.equals("tab")) {
			if (args.length == 2) {
				return filter(List.of("render"), args[1]);
			}

			if (args.length == 3) {
				return filter(playerNames(), args[2]);
			}

			if (args.length == 4) {
				return filter(List.of("plain", "mini", "legacy"), args[3]);
			}

			return List.of();
		}

		if (group.equals("bridge")) {
			if (args.length == 2) {
				return filter(List.of("stats", "last", "reset"), args[1]);
			}

			if (args.length == 3 && args[1].equalsIgnoreCase("stats")) {
				List<String> backends = new ArrayList<>();
				proxyServer.getAllServers().forEach(server -> backends.add(server.getServerInfo().getName()));
				return filter(backends, args[2]);
			}

			if (args.length == 3 && args[1].equalsIgnoreCase("last")) {
				return filter(playerNames(), args[2]);
			}
		}

		return List.of();
	}

	private void executeTab(CommandSource source, String[] args) {
		if (args.length < 3 || !args[1].equalsIgnoreCase("render")) {
			sendUsage(source);
			return;
		}

		Player viewer = findPlayer(args[2]).orElse(null);

		if (viewer == null) {
			source.sendRichMessage("<red>❌ Không tìm thấy người chơi đang online: <white>" + MM.escapeTags(args[2]) + "</white></red>");
			return;
		}

		VelocityTabListRenderer.Format format = VelocityTabListRenderer.Format.parse(args.length > 3 ? args[3] : null);
		TabListCapture capture = tabListRenderer.capture(viewer);
		sendLines(source, tabListRenderer.renderLines(capture, format));
	}

	private void executeBridge(CommandSource source, String[] args) {
		if (args.length < 2) {
			sendUsage(source);
			return;
		}

		String action = args[1].toLowerCase(Locale.ROOT);

		if (action.equals("stats")) {
			sendLines(source, bridgeObserver.statsLines(args.length > 2 ? args[2] : null));
			return;
		}

		if (action.equals("reset")) {
			bridgeObserver.reset();
			source.sendRichMessage("<green>✔ Đã đặt lại bộ đếm tab bridge.</green>");
			return;
		}

		if (action.equals("last")) {
			if (args.length < 3) {
				sendUsage(source);
				return;
			}

			Optional<Player> player = findPlayer(args[2]);
			UUID playerId = player.map(Player::getUniqueId).orElseGet(() -> parseUuid(args[2]));

			if (playerId == null) {
				source.sendRichMessage("<red>❌ Không tìm thấy người chơi: <white>" + MM.escapeTags(args[2]) + "</white></red>");
				return;
			}

			int limit = DEFAULT_RECENT;

			if (args.length > 3) {
				try {
					limit = Math.min(MAX_RECENT, Math.max(1, Integer.parseInt(args[3])));
				} catch (NumberFormatException ignored) {
					limit = DEFAULT_RECENT;
				}
			}

			String name = player.map(Player::getUsername).orElse(args[2]);
			sendLines(source, bridgeObserver.recentLines(playerId, name, limit));
			return;
		}

		sendUsage(source);
	}

	private void sendUsage(CommandSource source) {
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("tab"), CommandStrings.literal("render"), CommandStrings.required("player", "text"), CommandStrings.optional("plain|mini|legacy", "text")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("bridge"), CommandStrings.literal("stats"), CommandStrings.optional("backend", "text")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("bridge"), CommandStrings.literal("last"), CommandStrings.required("player", "text"), CommandStrings.optional("count", "int")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("bridge"), CommandStrings.literal("reset")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("parse"), CommandStrings.required("text", "text")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("resolve"), CommandStrings.required("player|-", "text"), CommandStrings.required("text", "text")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("render"), CommandStrings.required("player|-", "text"), CommandStrings.required("template", "text")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("list"), CommandStrings.optional("namespace", "text")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("tabids")));
		source.sendRichMessage(CommandStrings.usage("/luna placeholder", CommandStrings.literal("migrate"), CommandStrings.optional("apply", "text")));
	}

	private void sendLines(CommandSource source, List<String> lines) {
		for (String line : lines) {
			source.sendMessage(Component.text(line));
		}
	}

	private Optional<Player> findPlayer(String reference) {
		Optional<Player> byName = proxyServer.getPlayer(reference);

		if (byName.isPresent()) {
			return byName;
		}

		UUID uuid = parseUuid(reference);

		if (uuid == null) {
			return Optional.empty();
		}

		return proxyServer.getPlayer(uuid);
	}

	private UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private List<String> playerNames() {
		List<String> names = new ArrayList<>();
		proxyServer.getAllPlayers().forEach(player -> names.add(player.getUsername()));
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	private List<String> filter(List<String> candidates, String prefix) {
		String lowered = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
		List<String> matches = new ArrayList<>();

		for (String candidate : candidates) {
			if (candidate.toLowerCase(Locale.ROOT).startsWith(lowered)) {
				matches.add(candidate);
			}
		}

		return matches;
	}
}
