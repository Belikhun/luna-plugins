package dev.belikhun.luna.core.api.gui;

import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.api.ui.LunaUi;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class NumberSelectorGui implements Listener {
	private static final int[] STEPS = {1, 2, 4, 8, 10, 16, 20, 32, 50, 64, 100, 128, 200, 256, 500, 512, 1000};
	private static final int[] DECREASE_SLOTS = {1, 2, 3, 4, 5, 10, 11, 12, 14, 19, 20, 21, 23, 28, 29, 30, 32};
	private static final int[] INCREASE_SLOTS = {8, 15, 16, 17, 22, 24, 25, 26, 31, 33, 34, 35, 39, 42, 43, 44, 53};
	private static final int VALUE_SLOT = 13;
	private static final int INFO_SLOT = 31;
	private static final int CONFIRM_SLOT = 40;
	private static final int MANUAL_SLOT = 49;
	private static final int CANCEL_SLOT = 45;

	private final JavaPlugin plugin;
	private final GuiManager guiManager;
	private final PlainTextComponentSerializer plainText;
	private final Map<UUID, Session> activeSessions;
	private final Map<UUID, Session> waitingManualInput;
	private final Set<UUID> suppressClose;
	private final Set<UUID> submitting;

	public NumberSelectorGui(JavaPlugin plugin, GuiManager guiManager) {
		this.plugin = plugin;
		this.guiManager = guiManager;
		this.plainText = PlainTextComponentSerializer.plainText();
		this.activeSessions = new ConcurrentHashMap<>();
		this.waitingManualInput = new ConcurrentHashMap<>();
		this.suppressClose = ConcurrentHashMap.newKeySet();
		this.submitting = ConcurrentHashMap.newKeySet();
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
	}

	public void open(Player player, Request request) {
		double value = clamp(request.initialValue(), request.minValue(), request.maxValue());
		openSession(player, new Session(null, request, value));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onChat(AsyncChatEvent event) {
		Player player = event.getPlayer();
		UUID playerId = player.getUniqueId();
		Session waiting = waitingManualInput.get(playerId);
		if (waiting == null) {
			return;
		}

		event.setCancelled(true);
		String raw = plainText.serialize(event.message()).trim();
		plugin.getServer().getScheduler().runTask(plugin, () -> handleManualInput(player, waiting, raw));
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		Session session = activeSessions.get(playerId);
		if (session == null || session.view() == null) {
			return;
		}
		if (!event.getInventory().equals(session.view().getInventory())) {
			return;
		}

		activeSessions.remove(playerId);
		if (suppressClose.remove(playerId)) {
			return;
		}
		if (submitting.remove(playerId)) {
			return;
		}

		session.request().onCloseWithoutSubmit().accept(player);
	}

	private void handleManualInput(Player player, Session session, String raw) {
		UUID playerId = player.getUniqueId();
		if (raw.isBlank() || raw.equalsIgnoreCase("huy") || raw.equalsIgnoreCase("cancel")) {
			waitingManualInput.remove(playerId);
			openSession(player, session);
			return;
		}

		double parsed;
		try {
			parsed = parseNumber(raw, session.request().integerMode());
		} catch (NumberFormatException exception) {
			player.sendMessage(mm("<red>❌ Giá trị không hợp lệ. Hãy nhập số.</red>"));
			player.sendMessage(mm("<yellow>ℹ Gõ <white>huy</white> để quay lại bộ chọn số.</yellow>"));
			return;
		}

		waitingManualInput.remove(playerId);
		openSession(player, session.withValue(clamp(parsed, session.request().minValue(), session.request().maxValue())));
	}

	private void openSession(Player player, Session session) {
		UUID playerId = player.getUniqueId();
		Request request = session.request();
		double value = clamp(session.value(), request.minValue(), request.maxValue());

		GuiView view = new GuiView(54, request.title());
		guiManager.track(view);

		view.setItem(VALUE_SLOT, valueItem(request, value));
		view.setItem(INFO_SLOT, infoItem(request, value));

		for (int i = 0; i < STEPS.length; i++) {
			int step = STEPS[i];
			int decreaseSlot = DECREASE_SLOTS[i];
			int increaseSlot = INCREASE_SLOTS[i];
			view.setItem(decreaseSlot, adjustItem(step, false), (clicker, click, gui) -> {
				double next = clamp(value - step, request.minValue(), request.maxValue());
				openSession(clicker, session.withValue(next));
			});
			view.setItem(increaseSlot, adjustItem(step, true), (clicker, click, gui) -> {
				double next = clamp(value + step, request.minValue(), request.maxValue());
				openSession(clicker, session.withValue(next));
			});
		}

		view.setItem(CONFIRM_SLOT, actionItem(Material.LIME_CONCRETE, "<green>✔ Xác nhận", List.of(
			line(LunaPalette.SUCCESS_500, "Lưu giá trị hiện tại"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		)), (clicker, click, gui) -> {
			activeSessions.remove(playerId);
			submitting.add(playerId);
			suppressClose.add(playerId);
			clicker.closeInventory();
			plugin.getServer().getScheduler().runTask(plugin, () -> {
				try {
					request.onSubmit().accept(clicker, normalizeOutput(value, request.integerMode()));
				} finally {
					submitting.remove(playerId);
					suppressClose.remove(playerId);
				}
			});
		});

		view.setItem(MANUAL_SLOT, actionItem(Material.NAME_TAG, "<aqua>✎ Nhập thủ công", List.of(
			line(LunaPalette.INFO_500, "Nhập số trên chat"),
			line(LunaPalette.NEUTRAL_100, "Gõ <white>huy</white> để quay lại")
		)), (clicker, click, gui) -> {
			activeSessions.remove(playerId);
			waitingManualInput.put(playerId, session);
			suppressClose.add(playerId);
			clicker.closeInventory();
			suppressClose.remove(playerId);
			clicker.sendMessage(mm("<aqua>✦ Nhập số mới trên chat. Gõ <white>huy</white> để quay lại.</aqua>"));
		});

		view.setItem(CANCEL_SLOT, actionItem(Material.BARRIER, "<red>✖ Hủy", List.of(
			line(LunaPalette.DANGER_500, "Đóng mà không lưu")
		)), (clicker, click, gui) -> {
			activeSessions.remove(playerId);
			suppressClose.add(playerId);
			clicker.closeInventory();
			suppressClose.remove(playerId);
			request.onCloseWithoutSubmit().accept(clicker);
		});

		activeSessions.put(playerId, new Session(view, request, value));
		if (player.getOpenInventory() != null) {
			suppressClose.add(playerId);
		}
		view.open(player);
		suppressClose.remove(playerId);
	}

	private ItemStack valueItem(Request request, double value) {
		return actionItem(request.displayMaterial(), "<aqua>◆ " + request.label(), List.of(
			line(LunaPalette.NEUTRAL_100, "Giá trị hiện tại:"),
			line(LunaPalette.INFO_500, "<white>" + formatValue(request, value) + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Min: <white>" + formatBoundary(request, request.minValue()) + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Max: <white>" + formatBoundary(request, request.maxValue()) + "</white>")
		));
	}

	private ItemStack infoItem(Request request, double value) {
		return actionItem(Material.BOOK, "<yellow>⌚ Hướng dẫn", List.of(
			line(LunaPalette.WARNING_500, "Dùng nút +/- để chỉnh nhanh"),
			line(LunaPalette.NEUTRAL_100, "Bước hỗ trợ: <white>1..1000</white>"),
			line(LunaPalette.NEUTRAL_100, "Kiểu số: <white>" + (request.integerMode() ? "Số nguyên" : "Số thập phân") + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		));
	}

	private ItemStack adjustItem(int amount, boolean increase) {
		String sign = increase ? "+" : "-";
		String color = increase ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500;
		Material material = increase ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
		return actionItem(material, "<color:" + color + ">" + sign + amount + "</color>", List.of(
			line(color, (increase ? "Tăng" : "Giảm") + " <white>" + amount + "</white>")
		));
	}

	private ItemStack actionItem(Material material, String title, List<String> loreLines) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		meta.displayName(mm(title));
		meta.lore(loreLines.stream().map(LunaUi::mini).toList());
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		item.setItemMeta(meta);
		return item;
	}

	private String line(String color, String text) {
		return "<color:" + color + ">" + text + "</color>";
	}

	private double normalizeOutput(double value, boolean integerMode) {
		if (!integerMode) {
			return value;
		}
		return Math.rint(value);
	}

	private String formatValue(Request request, double value) {
		return request.valueFormatter().apply(normalizeOutput(value, request.integerMode()));
	}

	private String formatBoundary(Request request, double value) {
		return request.valueFormatter().apply(normalizeOutput(value, request.integerMode()));
	}

	private double parseNumber(String input, boolean integerMode) {
		String normalized = input.trim().replace(',', '.');
		if (integerMode) {
			return Long.parseLong(normalized);
		}
		return Double.parseDouble(normalized);
	}

	private double clamp(double value, double min, double max) {
		double lower = Math.min(min, max);
		double upper = Math.max(min, max);
		if (value < lower) {
			return lower;
		}
		if (value > upper) {
			return upper;
		}
		return value;
	}

	private Component mm(String text) {
		return LunaUi.mini(text);
	}

	public static Request request(Component title, String label, BiConsumer<Player, Double> onSubmit, Consumer<Player> onCloseWithoutSubmit) {
		return new Request(
			title,
			label,
			Material.PAPER,
			0D,
			0D,
			4096D,
			true,
			value -> {
				long whole = Math.round(value);
				return String.format(Locale.ROOT, "%d", whole);
			},
			onSubmit,
			onCloseWithoutSubmit
		);
	}

	public record Request(
		Component title,
		String label,
		Material displayMaterial,
		double initialValue,
		double minValue,
		double maxValue,
		boolean integerMode,
		Function<Double, String> valueFormatter,
		BiConsumer<Player, Double> onSubmit,
		Consumer<Player> onCloseWithoutSubmit
	) {
		public Request {
			if (title == null) {
				title = LunaUi.guiTitle("Chọn số");
			}
			if (label == null || label.isBlank()) {
				label = "Giá trị";
			}
			if (displayMaterial == null || displayMaterial.isAir()) {
				displayMaterial = Material.PAPER;
			}
			if (valueFormatter == null) {
				if (integerMode) {
					valueFormatter = value -> String.format(Locale.ROOT, "%d", Math.round(value));
				} else {
					valueFormatter = value -> String.format(Locale.ROOT, "%.2f", value);
				}
			}
			if (onSubmit == null) {
				throw new IllegalArgumentException("onSubmit cannot be null");
			}
			if (onCloseWithoutSubmit == null) {
				onCloseWithoutSubmit = player -> {};
			}
		}

		public Request withDisplayMaterial(Material material) {
			return new Request(title, label, material, initialValue, minValue, maxValue, integerMode, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withInitialValue(double value) {
			return new Request(title, label, displayMaterial, value, minValue, maxValue, integerMode, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withRange(double min, double max) {
			return new Request(title, label, displayMaterial, initialValue, min, max, integerMode, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withIntegerMode(boolean value) {
			return new Request(title, label, displayMaterial, initialValue, minValue, maxValue, value, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withValueFormatter(Function<Double, String> formatter) {
			return new Request(title, label, displayMaterial, initialValue, minValue, maxValue, integerMode, formatter, onSubmit, onCloseWithoutSubmit);
		}
	}

	private record Session(
		GuiView view,
		Request request,
		double value
	) {
		private Session withValue(double next) {
			return new Session(view, request, next);
		}
	}
}
