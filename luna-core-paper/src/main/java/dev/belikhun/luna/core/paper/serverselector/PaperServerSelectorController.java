package dev.belikhun.luna.core.paper.serverselector;

import dev.belikhun.luna.core.api.gui.GuiManager;
import dev.belikhun.luna.core.api.gui.GuiView;
import dev.belikhun.luna.core.api.heartbeat.BackendServerStatus;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messaging.CoreServerSelectorMessageChannels;
import dev.belikhun.luna.core.api.messaging.PluginMessageBus;
import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.ui.LunaUi;
import dev.belikhun.luna.core.paper.heartbeat.PaperBackendStatusView;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperServerSelectorController implements Listener {
	private static final int GUI_SIZE = 54;
	private static final int PAGE_SIZE = 45;

	private final JavaPlugin plugin;
	private final PaperBackendStatusView statusView;
	private final PluginMessageBus<Player, Player> messaging;
	private final LunaLogger logger;
	private final boolean diagnosticsEnabled;
	private final long refreshWarnThresholdMs;
	private final GuiManager guiManager;
	private final Map<UUID, Integer> openPages;
	private final Map<UUID, SelectorPayload> payloadByPlayer;

	public PaperServerSelectorController(
		JavaPlugin plugin,
		PaperBackendStatusView statusView,
		PluginMessageBus<Player, Player> messaging,
		LunaLogger logger,
		boolean diagnosticsEnabled,
		long refreshWarnThresholdMs
	) {
		this.plugin = plugin;
		this.statusView = statusView;
		this.messaging = messaging;
		this.logger = logger.scope("ServerSelector");
		this.diagnosticsEnabled = diagnosticsEnabled;
		this.refreshWarnThresholdMs = Math.max(1L, refreshWarnThresholdMs);
		this.guiManager = new GuiManager();
		this.openPages = new ConcurrentHashMap<>();
		this.payloadByPlayer = new ConcurrentHashMap<>();

		plugin.getServer().getPluginManager().registerEvents(guiManager, plugin);
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		statusView.addUpdateListener(() -> plugin.getServer().getScheduler().runTask(plugin, this::refreshOpenMenus));

		messaging.registerOutgoing(CoreServerSelectorMessageChannels.CONNECT_REQUEST);
		messaging.registerIncoming(CoreServerSelectorMessageChannels.OPEN_MENU, context -> {
			Player player = context.source();
			PluginMessageReader reader = PluginMessageReader.of(context.payload());
			SelectorPayload payload = parsePayload(reader);
			payloadByPlayer.put(player.getUniqueId(), payload);
			plugin.getServer().getScheduler().runTask(plugin, () -> open(player, openPages.getOrDefault(player.getUniqueId(), 0)));
			return dev.belikhun.luna.core.api.messaging.PluginMessageDispatchResult.HANDLED;
		});
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		openPages.remove(event.getPlayer().getUniqueId());
		payloadByPlayer.remove(event.getPlayer().getUniqueId());
	}

	public void open(Player player, int page) {
		SelectorPayload payload = payloadByPlayer.get(player.getUniqueId());
		Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage = layoutByPage(payload);
		int maxPage = layoutByPage.isEmpty() ? 0 : layoutByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
		int currentPage = Math.max(0, Math.min(page, maxPage));
		openPages.put(player.getUniqueId(), currentPage);

		String title = payload == null ? "Danh Sách Máy Chủ" : payload.guiTitle();
		GuiView view = new GuiView(GUI_SIZE, LunaUi.guiTitle(applyTemplate(title, Map.of("player_name", player.getName()))));
		guiManager.track(view);

		Map<Integer, ServerRenderEntry> pageLayout = layoutByPage.getOrDefault(currentPage, Map.of());
		for (Map.Entry<Integer, ServerRenderEntry> entry : pageLayout.entrySet()) {
			int slot = entry.getKey();
			ServerRenderEntry renderEntry = entry.getValue();
			BackendServerStatus status = renderEntry.status();
			ServerPayload serverPayload = renderEntry.payload();
			String permission = serverPayload == null ? "" : serverPayload.permission();
			boolean noPermission = permission != null && !permission.isBlank() && !player.hasPermission(permission);
			view.setItem(slot, buildServerItem(status, serverPayload, payload, noPermission), (clicker, event, gui) -> {
				if (noPermission) {
					clicker.sendMessage(LunaUi.mini("<red>❌ Bạn không có quyền vào máy chủ này.</red>"));
					return;
				}
				messaging.send(clicker, CoreServerSelectorMessageChannels.CONNECT_REQUEST, writer -> {
					writer.writeUtf(clicker.getUniqueId().toString());
					writer.writeUtf(status.serverName());
				});
				clicker.closeInventory();
			});
		}

		if (currentPage > 0) {
			view.setItem(45, LunaUi.item(Material.ARROW, "<yellow>← Trang trước</yellow>", List.of()), (clicker, event, gui) -> open(clicker, currentPage - 1));
		}
		if (currentPage < maxPage) {
			view.setItem(53, LunaUi.item(Material.ARROW, "<yellow>Trang sau →</yellow>", List.of()), (clicker, event, gui) -> open(clicker, currentPage + 1));
		}
		view.setItem(49, LunaUi.item(Material.BARRIER, "<red>Đóng</red>", List.of()), (clicker, event, gui) -> clicker.closeInventory());

		player.openInventory(view.getInventory());
	}

	private void refreshOpenMenus() {
		long startedAt = System.currentTimeMillis();
		int refreshed = 0;
		int openCount = openPages.size();
		for (Map.Entry<UUID, Integer> entry : new LinkedHashMap<>(openPages).entrySet()) {
			Player player = plugin.getServer().getPlayer(entry.getKey());
			if (player == null || !player.isOnline()) {
				openPages.remove(entry.getKey());
				continue;
			}
			if (player.getOpenInventory() == null || player.getOpenInventory().getTopInventory() == null) {
				continue;
			}
			open(player, entry.getValue());
			refreshed++;
		}

		if (diagnosticsEnabled) {
			long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
			if (elapsedMs > refreshWarnThresholdMs) {
				logger.warn("Selector diagnostics: refresh mất " + elapsedMs + "ms, viewers=" + refreshed + "/" + openCount);
			}
		}
	}

	private List<BackendServerStatus> sortedStatuses(SelectorPayload payload) {
		List<BackendServerStatus> statuses = new ArrayList<>();
		if (payload != null && !payload.servers().isEmpty()) {
			for (ServerPayload item : payload.servers().values()) {
				BackendServerStatus status = statusView.status(item.backendName()).orElseGet(() -> new BackendServerStatus(
					item.backendName(),
					item.displayName(),
					item.accentColor(),
					false,
					0L,
					null
				));
				statuses.add(status);
			}
		} else {
			statuses = new ArrayList<>(statusView.snapshot().values());
		}
		statuses.sort(Comparator.comparing(status -> status.serverName().toLowerCase(Locale.ROOT)));
		return statuses;
	}

	private Map<Integer, Map<Integer, ServerRenderEntry>> layoutByPage(SelectorPayload payload) {
		List<ServerRenderEntry> entries = sortedEntries(payload);
		Map<Integer, Map<Integer, ServerRenderEntry>> byPage = new LinkedHashMap<>();
		List<ServerRenderEntry> unresolved = new ArrayList<>();

		for (ServerRenderEntry entry : entries) {
			ServerPayload payloadEntry = entry.payload();
			if (payloadEntry == null || payloadEntry.slot() == null || payloadEntry.page() == null) {
				unresolved.add(entry);
				continue;
			}

			int slot = payloadEntry.slot();
			int page = payloadEntry.page() - 1;
			if (slot < 0 || slot >= PAGE_SIZE || page < 0) {
				unresolved.add(entry);
				continue;
			}
			byPage.computeIfAbsent(page, ignored -> new LinkedHashMap<>()).put(slot, entry);
		}

		int pagePointer = 0;
		for (ServerRenderEntry entry : unresolved) {
			while (true) {
				Map<Integer, ServerRenderEntry> pageLayout = byPage.computeIfAbsent(pagePointer, ignored -> new LinkedHashMap<>());
				int slot = firstFreeSlot(pageLayout);
				if (slot == -1) {
					pagePointer++;
					continue;
				}
				pageLayout.put(slot, entry);
				break;
			}
		}

		return byPage;
	}

	private List<ServerRenderEntry> sortedEntries(SelectorPayload payload) {
		List<ServerRenderEntry> entries = new ArrayList<>();
		if (payload != null && !payload.servers().isEmpty()) {
			for (ServerPayload item : payload.servers().values()) {
				BackendServerStatus status = statusView.status(item.backendName()).orElseGet(() -> new BackendServerStatus(
					item.backendName(),
					item.displayName(),
					item.accentColor(),
					false,
					0L,
					null
				));
				entries.add(new ServerRenderEntry(status, item));
			}
		} else {
			for (BackendServerStatus status : statusView.snapshot().values()) {
				entries.add(new ServerRenderEntry(status, null));
			}
		}
		entries.sort(Comparator.comparing(entry -> entry.status().serverName().toLowerCase(Locale.ROOT)));
		return entries;
	}

	private int firstFreeSlot(Map<Integer, ServerRenderEntry> pageLayout) {
		for (int slot = 0; slot < PAGE_SIZE; slot++) {
			if (!pageLayout.containsKey(slot)) {
				return slot;
			}
		}
		return -1;
	}

	private org.bukkit.inventory.ItemStack buildServerItem(
		BackendServerStatus status,
		ServerPayload serverPayload,
		SelectorPayload payload,
		boolean noPermission
	) {
		String statusText = resolveStatus(status, noPermission);
		Material material = resolveMaterial(statusText);

		int onlinePlayers = status.stats() == null ? 0 : status.stats().onlinePlayers();
		int maxPlayers = status.stats() == null ? 0 : status.stats().maxPlayers();
		String display = serverPayload != null && !serverPayload.displayName().isBlank()
			? serverPayload.displayName()
			: (status.serverDisplay() == null || status.serverDisplay().isBlank() ? status.serverName() : status.serverDisplay());

		TemplatePayload template = payload == null
			? TemplatePayload.defaultTemplate()
			: payload.resolveTemplate(serverPayload, statusText);

		Map<String, String> values = placeholderValues(status, display, statusText, onlinePlayers, maxPlayers);
		List<Component> lore = new ArrayList<>();
		for (String headerLine : template.headerLines()) {
			lore.add(LunaUi.mini(applyTemplate(headerLine == null ? "" : headerLine, values)));
		}

		List<String> description = serverPayload == null ? List.of() : serverPayload.description(statusText);
		for (String line : description) {
			Map<String, String> withLine = new LinkedHashMap<>(values);
			withLine.put("line", line == null ? "" : line);
			lore.add(LunaUi.mini(applyTemplate(template.bodyLineTemplate(), withLine)));
		}

		for (String footerLine : template.footerLines()) {
			lore.add(LunaUi.mini(applyTemplate(footerLine == null ? "" : footerLine, values)));
		}
		lore.add(LunaUi.mini("<gray>Trạng thái: <white>" + statusText + "</white></gray>"));
		lore.add(LunaUi.mini("<gray>Người chơi: <white>" + onlinePlayers + "/" + maxPlayers + "</white></gray>"));
		lore.add(LunaUi.mini(noPermission ? "<red>Không có quyền truy cập</red>" : "<yellow>Nhấn để kết nối</yellow>"));

		return LunaUi.item(material, applyTemplate(template.nameTemplate(), values), lore);
	}

	private Material resolveMaterial(String statusText) {
		if ("NOP".equals(statusText)) {
			return Material.GRAY_CONCRETE;
		}
		if ("OFFLINE".equals(statusText)) {
			return Material.RED_CONCRETE;
		}
		if ("MAINT".equals(statusText)) {
			return Material.YELLOW_CONCRETE;
		}
		return Material.LIME_CONCRETE;
	}

	private String resolveStatus(BackendServerStatus status, boolean noPermission) {
		if (noPermission) {
			return "NOP";
		}
		if (!status.online()) {
			return "OFFLINE";
		}
		if (status.stats() != null && status.stats().whitelistEnabled()) {
			return "MAINT";
		}
		return "ONLINE";
	}

	private Map<String, String> placeholderValues(
		BackendServerStatus status,
		String display,
		String statusText,
		int online,
		int max
	) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("server_name", status.serverName());
		values.put("server_display", display);
		values.put("server_accent_color", status.serverAccentColor() == null ? "" : status.serverAccentColor());
		values.put("server_status", statusText);
		values.put("online", String.valueOf(online));
		values.put("max", String.valueOf(max));
		values.put("version", status.stats() == null ? "unknown" : status.stats().version());
		values.put("tps", status.stats() == null ? "0.00" : String.format(Locale.US, "%.2f", status.stats().tps()));
		values.put("uptime", String.valueOf(status.stats() == null ? 0L : Math.max(0L, status.stats().uptimeMillis() / 1000L)));
		return values;
	}

	private String applyTemplate(String template, Map<String, String> values) {
		String output = template == null ? "" : template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			output = output.replace("%" + entry.getKey() + "%", entry.getValue() == null ? "" : entry.getValue());
		}
		return output;
	}

	private SelectorPayload parsePayload(PluginMessageReader reader) {
		try {
			String mode = reader.readUtf();
			if (!"open-v3".equalsIgnoreCase(mode)) {
				return SelectorPayload.empty();
			}

			String guiTitle = reader.readUtf();
			String name = reader.readUtf();
			List<String> header = List.copyOf(readLines(reader));
			String bodyLine = reader.readUtf();
			List<String> footer = List.copyOf(readLines(reader));
			Map<String, TemplateOverridePayload> globalTemplateByStatus = readTemplateOverrides(reader);

			TemplatePayload baseTemplate = new TemplatePayload(name, header, bodyLine, footer, globalTemplateByStatus);
			int serverCount = Math.max(0, reader.readInt());
			Map<String, ServerPayload> servers = new LinkedHashMap<>();
			for (int i = 0; i < serverCount; i++) {
				String backendName = reader.readUtf();
				String display = reader.readUtf();
				String accent = reader.readUtf();
				String permission = reader.readUtf();
				int rawSlot = reader.readInt();
				int rawPage = reader.readInt();
				Integer slot = rawSlot < 0 ? null : rawSlot;
				Integer page = rawPage < 0 ? null : rawPage;
				List<String> description = List.copyOf(readLines(reader));

				int statusDescriptionCount = Math.max(0, reader.readInt());
				Map<String, List<String>> descriptionByStatus = new LinkedHashMap<>();
				for (int index = 0; index < statusDescriptionCount; index++) {
					String statusKey = reader.readUtf().toUpperCase(Locale.ROOT);
					descriptionByStatus.put(statusKey, List.copyOf(readLines(reader)));
				}

				TemplatePayload serverTemplate = null;
				boolean hasServerTemplate = reader.readBoolean();
				if (hasServerTemplate) {
					serverTemplate = new TemplatePayload(
						reader.readUtf(),
						List.copyOf(readLines(reader)),
						reader.readUtf(),
						List.copyOf(readLines(reader)),
						readTemplateOverrides(reader)
					);
				}

				servers.put(backendName.toLowerCase(Locale.ROOT), new ServerPayload(
					backendName,
					display,
					accent,
					permission,
					slot,
					page,
					description,
					Map.copyOf(descriptionByStatus),
					serverTemplate
				));
			}
			return new SelectorPayload(guiTitle, baseTemplate, servers);
		} catch (Exception ignored) {
			return SelectorPayload.empty();
		}
	}

	private List<String> readLines(PluginMessageReader reader) {
		int lineCount = Math.max(0, reader.readInt());
		List<String> lines = new ArrayList<>(lineCount);
		for (int i = 0; i < lineCount; i++) {
			lines.add(reader.readUtf());
		}
		return lines;
	}

	private Map<String, TemplateOverridePayload> readTemplateOverrides(PluginMessageReader reader) {
		int overrideCount = Math.max(0, reader.readInt());
		Map<String, TemplateOverridePayload> overrides = new LinkedHashMap<>();
		for (int i = 0; i < overrideCount; i++) {
			String status = reader.readUtf().toUpperCase(Locale.ROOT);
			String name = null;
			List<String> header = null;
			String bodyLine = null;
			List<String> footer = null;

			if (reader.readBoolean()) {
				name = reader.readUtf();
			}
			if (reader.readBoolean()) {
				header = List.copyOf(readLines(reader));
			}
			if (reader.readBoolean()) {
				bodyLine = reader.readUtf();
			}
			if (reader.readBoolean()) {
				footer = List.copyOf(readLines(reader));
			}

			overrides.put(status, new TemplateOverridePayload(name, header, bodyLine, footer));
		}
		return Map.copyOf(overrides);
	}

	private record SelectorPayload(
		String guiTitle,
		TemplatePayload template,
		Map<String, ServerPayload> servers
	) {
		static SelectorPayload empty() {
			return new SelectorPayload("Danh Sách Máy Chủ", TemplatePayload.defaultTemplate(), Map.of());
		}

		ServerPayload server(String backendName) {
			if (backendName == null) {
				return null;
			}
			return servers.get(backendName.toLowerCase(Locale.ROOT));
		}

		TemplatePayload resolveTemplate(ServerPayload serverPayload, String status) {
			TemplatePayload resolved = template.applyOverride(template.byStatus().get(status));
			if (serverPayload == null || serverPayload.template() == null) {
				return resolved;
			}
			resolved = resolved.merge(serverPayload.template());
			return resolved.applyOverride(serverPayload.template().byStatus().get(status));
		}
	}

	private record TemplatePayload(
		String nameTemplate,
		List<String> headerLines,
		String bodyLineTemplate,
		List<String> footerLines,
		Map<String, TemplateOverridePayload> byStatus
	) {
		static TemplatePayload defaultTemplate() {
			return new TemplatePayload("<b>%server_display%</b>", List.of(), "%line%", List.of(), Map.of());
		}

		TemplatePayload applyOverride(TemplateOverridePayload override) {
			if (override == null) {
				return this;
			}
			return new TemplatePayload(
				override.nameTemplate() != null ? override.nameTemplate() : nameTemplate,
				override.headerLines() != null ? override.headerLines() : headerLines,
				override.bodyLineTemplate() != null ? override.bodyLineTemplate() : bodyLineTemplate,
				override.footerLines() != null ? override.footerLines() : footerLines,
				byStatus
			);
		}

		TemplatePayload merge(TemplatePayload serverTemplate) {
			return new TemplatePayload(
				serverTemplate.nameTemplate(),
				serverTemplate.headerLines(),
				serverTemplate.bodyLineTemplate(),
				serverTemplate.footerLines(),
				serverTemplate.byStatus()
			);
		}
	}

	private record TemplateOverridePayload(
		String nameTemplate,
		List<String> headerLines,
		String bodyLineTemplate,
		List<String> footerLines
	) {
	}

	private record ServerPayload(
		String backendName,
		String displayName,
		String accentColor,
		String permission,
		Integer slot,
		Integer page,
		List<String> description,
		Map<String, List<String>> descriptionByStatus,
		TemplatePayload template
	) {
		List<String> description(String status) {
			if (status == null) {
				return description;
			}
			List<String> override = descriptionByStatus.get(status.toUpperCase(Locale.ROOT));
			return override == null ? description : override;
		}
	}

	private record ServerRenderEntry(
		BackendServerStatus status,
		ServerPayload payload
	) {
	}
}
