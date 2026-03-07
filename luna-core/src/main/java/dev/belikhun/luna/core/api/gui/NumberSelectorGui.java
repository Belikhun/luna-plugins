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
	private static final int[] NORMAL_STEPS = {500, 100, 50, 20, 10, 5, 2, 1};
	private static final int[] STACK_STEPS = {8, 4, 2, 1, 128, 64, 32, 16};
	private static final int[] NORMAL_DEC_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12};
	private static final int[] NORMAL_INC_SLOTS = {8, 7, 6, 5, 17, 16, 15, 14};
	private static final int[] STACK_DEC_SLOTS = {27, 28, 29, 30, 36, 37, 38, 39};
	private static final int[] STACK_INC_SLOTS = {35, 34, 33, 32, 44, 43, 42, 41};
	private static final int VALUE_SLOT = 22;
	private static final int INFO_SLOT = 40;
	private static final int CONFIRM_SLOT = 53;
	private static final int MANUAL_SLOT = 49;
	private static final int CANCEL_SLOT = 45;
	private static final int FOOTER_START = 45;
	private static final int FOOTER_END = 53;

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

		GuiView view = new GuiView(54, titleWithValue(request, value));
		guiManager.track(view);
		if (request.integerMode()) {
			view.getInventory().setMaxStackSize(maxDisplayStackSize(request));
		}
		fillFooter(view);

		view.setItem(VALUE_SLOT, valueItem(request, value));
		view.setItem(INFO_SLOT, infoItem(request, value));

		for (int i = 0; i < NORMAL_STEPS.length; i++) {
			int step = NORMAL_STEPS[i];
			int decSlot = NORMAL_DEC_SLOTS[i];
			int incSlot = NORMAL_INC_SLOTS[i];
			view.setItem(decSlot, stepItem(step, false, StepArea.NORMAL, value, request), (clicker, event, gui) -> {
				double next = clamp(value - step, request.minValue(), request.maxValue());
				openSession(clicker, session.withValue(next));
			});
			view.setItem(incSlot, stepItem(step, true, StepArea.NORMAL, value, request), (clicker, event, gui) -> {
				double next = clamp(value + step, request.minValue(), request.maxValue());
				openSession(clicker, session.withValue(next));
			});
		}

		for (int i = 0; i < STACK_STEPS.length; i++) {
			int step = STACK_STEPS[i];
			int decSlot = STACK_DEC_SLOTS[i];
			int incSlot = STACK_INC_SLOTS[i];
			view.setItem(decSlot, stepItem(step, false, StepArea.STACK, value, request), (clicker, event, gui) -> {
				double next = clamp(value - step, request.minValue(), request.maxValue());
				openSession(clicker, session.withValue(next));
			});
			view.setItem(incSlot, stepItem(step, true, StepArea.STACK, value, request), (clicker, event, gui) -> {
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
		java.util.ArrayList<String> lore = new java.util.ArrayList<>(List.of(
			line(LunaPalette.INFO_500, "ℹ Giá trị hiện tại"),
			line(LunaPalette.NEUTRAL_100, "<white>" + formatValue(request, value) + "</white>"),
			" ",
			line(LunaPalette.INFO_300, "⌚ Quy đổi stack"),
			line(LunaPalette.NEUTRAL_100, "<white>" + stackSummary(value, request.integerMode()) + "</white>"),
			" ",
			line(LunaPalette.WARNING_500, "↔ Khoảng cho phép"),
			line(LunaPalette.NEUTRAL_100, "Min: <white>" + formatBoundary(request, request.minValue()) + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Max: <white>" + formatBoundary(request, request.maxValue()) + "</white>")
		));

		ItemStack item = actionItem(request.displayMaterial(), request.displayItem(), "<aqua>◆ " + request.label(), lore);

		if (request.integerMode()) {
			int stackCap = maxDisplayStackSize(request);
			ItemMeta meta = item.getItemMeta();
			if (meta != null) {
				meta.setMaxStackSize(stackCap);
				item.setItemMeta(meta);
			}

			int amount = (int) Math.max(1D, Math.min(stackCap, Math.rint(normalizeOutput(value, true))));
			item.setAmount(amount);

			if (Math.rint(normalizeOutput(value, true)) > stackCap) {
				lore.add(" ");
				lore.add(line(LunaPalette.WARNING_500, "⚠ Icon chỉ đến 99"));
				lore.add(line(LunaPalette.NEUTRAL_100, "Số thật vẫn chính xác"));
				item = actionItem(request.displayMaterial(), item, "<aqua>◆ " + request.label(), lore);
				item.setAmount(amount);
			}
		}

		return item;
	}

	private ItemStack infoItem(Request request, double value) {
		return actionItem(Material.BOOK, "<yellow>⌚ Hướng dẫn", List.of(
			line(LunaPalette.WARNING_500, "Nửa trái: giảm theo bước"),
			line(LunaPalette.WARNING_500, "Nửa phải: tăng theo bước"),
			line(LunaPalette.NEUTRAL_100, "Bước thường: <white>1..500</white>"),
			line(LunaPalette.NEUTRAL_100, "Bước stack: <white>1..128</white>"),
			line(LunaPalette.NEUTRAL_100, "Kiểu số: <white>" + (request.integerMode() ? "Số nguyên" : "Số thập phân") + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		));
	}

	private ItemStack stepItem(int amount, boolean increase, StepArea area, double currentValue, Request request) {
		Material material;
		if (area == StepArea.STACK) {
			material = increase ? Material.LIME_STAINED_GLASS_PANE : Material.PINK_STAINED_GLASS_PANE;
		} else {
			material = increase ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
		}

		double nextValue = clamp(
			increase ? currentValue + amount : currentValue - amount,
			request.minValue(),
			request.maxValue()
		);
		String sign = increase ? "+" : "-";

		return actionItem(material, "<white>" + sign + "</white> <yellow>" + amount + "</yellow>", List.of(
			line(increase ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500, (increase ? "Tăng" : "Giảm") + " <white>" + amount + "</white>"),
			line(LunaPalette.INFO_300, "Sau khi áp dụng: <white>" + formatValue(request, nextValue) + "</white>"),
			line(LunaPalette.INFO_300, "Stack: <white>" + stackSummary(nextValue, request.integerMode()) + "</white>")
		));
	}

	private void fillFooter(GuiView view) {
		ItemStack filler = actionItem(Material.BLACK_STAINED_GLASS_PANE, "<color:#374151> </color>", List.of());
		for (int slot = FOOTER_START; slot <= FOOTER_END; slot++) {
			view.setItem(slot, filler);
		}
	}

	private ItemStack actionItem(Material material, String title, List<String> loreLines) {
		return actionItem(material, null, title, loreLines);
	}

	private ItemStack actionItem(Material material, ItemStack template, String title, List<String> loreLines) {
		ItemStack item;
		if (template != null && !template.getType().isAir()) {
			item = template.clone();
		} else {
			item = new ItemStack(material);
		}

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

	private String stackSummary(double value, boolean integerMode) {
		double normalized = normalizeOutput(value, integerMode);
		if (!integerMode) {
			return "n/a";
		}

		long whole = Math.max(0L, Math.round(normalized));
		long fullStacks = whole / 64L;
		long remainder = whole % 64L;
		return fullStacks + " stack + " + remainder;
	}

	private double normalizeOutput(double value, boolean integerMode) {
		if (!integerMode) {
			return value;
		}
		return Math.rint(value);
	}

	private String formatValue(Request request, double value) {
		return appendUnit(request.numberDisplayFormatter().apply(normalizeOutput(value, request.integerMode())), request.unit());
	}

	private String formatBoundary(Request request, double value) {
		return appendUnit(request.numberDisplayFormatter().apply(normalizeOutput(value, request.integerMode())), request.unit());
	}

	private String appendUnit(String valueText, String unit) {
		if (unit == null || unit.isBlank()) {
			return valueText;
		}

		return valueText + " " + unit;
	}

	private Component titleWithValue(Request request, double value) {
		return request.title().append(mm(
			" <color:" + LunaPalette.NEUTRAL_500 + ">•</color> <color:" + LunaPalette.NEUTRAL_700 + ">"
				+ formatValue(request, value)
				+ "</color>"
		));
	}

	private int maxDisplayStackSize(Request request) {
		double max = Math.max(1D, request.maxValue());
		return (int) Math.max(1D, Math.min(99D, Math.rint(max)));
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
			"",
			Material.PAPER,
			null,
			0D,
			0D,
			4096D,
			true,
			null,
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
		String unit,
		Material displayMaterial,
		ItemStack displayItem,
		double initialValue,
		double minValue,
		double maxValue,
		boolean integerMode,
		Function<Double, String> numberDisplayFormatter,
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
			if (unit == null) {
				unit = "";
			}
			if (displayMaterial == null || displayMaterial.isAir()) {
				displayMaterial = Material.PAPER;
			}
			if (displayItem != null && displayItem.getType().isAir()) {
				displayItem = null;
			}
			if (numberDisplayFormatter == null) {
				numberDisplayFormatter = valueFormatter;
			}
			if (valueFormatter == null) {
				if (integerMode) {
					valueFormatter = value -> String.format(Locale.ROOT, "%d", Math.round(value));
				} else {
					valueFormatter = value -> String.format(Locale.ROOT, "%.2f", value);
				}
			}
			if (numberDisplayFormatter == null) {
				numberDisplayFormatter = valueFormatter;
			}
			if (onSubmit == null) {
				throw new IllegalArgumentException("onSubmit cannot be null");
			}
			if (onCloseWithoutSubmit == null) {
				onCloseWithoutSubmit = player -> {};
			}
		}

		public Request withTitle(Component value) {
			return new Request(value, label, unit, displayMaterial, displayItem, initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withLabel(String value) {
			return new Request(title, value, unit, displayMaterial, displayItem, initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withUnit(String value) {
			return new Request(title, label, value, displayMaterial, displayItem, initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withDisplayMaterial(Material material) {
			return new Request(title, label, unit, material, displayItem, initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withDisplayItem(ItemStack itemStack) {
			return new Request(title, label, unit, displayMaterial, itemStack == null ? null : itemStack.clone(), initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withInitialValue(double value) {
			return new Request(title, label, unit, displayMaterial, displayItem, value, minValue, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withRange(double min, double max) {
			return new Request(title, label, unit, displayMaterial, displayItem, initialValue, min, max, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withMinValue(double value) {
			return new Request(title, label, unit, displayMaterial, displayItem, initialValue, value, maxValue, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withMaxValue(double value) {
			return new Request(title, label, unit, displayMaterial, displayItem, initialValue, minValue, value, integerMode, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withIntegerMode(boolean value) {
			return new Request(title, label, unit, displayMaterial, displayItem, initialValue, minValue, maxValue, value, numberDisplayFormatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withNumberDisplayFormatter(Function<Double, String> formatter) {
			return new Request(title, label, unit, displayMaterial, displayItem, initialValue, minValue, maxValue, integerMode, formatter, valueFormatter, onSubmit, onCloseWithoutSubmit);
		}

		public Request withValueFormatter(Function<Double, String> formatter) {
			// Backward-compatible alias for callers already using withValueFormatter.
			return new Request(title, label, unit, displayMaterial, displayItem, initialValue, minValue, maxValue, integerMode, formatter, formatter, onSubmit, onCloseWithoutSubmit);
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

	private enum StepArea {
		NORMAL,
		STACK
	}
}
