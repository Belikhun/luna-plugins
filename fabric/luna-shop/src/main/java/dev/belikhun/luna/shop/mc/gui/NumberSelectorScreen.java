package dev.belikhun.luna.shop.mc.gui;

import dev.belikhun.luna.core.api.ui.LunaPalette;
import dev.belikhun.luna.core.mc.text.LunaTextComponents;
import dev.belikhun.luna.core.mc.ui.ChatPrompts;
import dev.belikhun.luna.core.mc.ui.LunaChestMenuBase;
import dev.belikhun.luna.core.mc.ui.LunaItems;
import dev.belikhun.luna.core.mc.ui.LunaMenuHost;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * "How many?" as a screen: two rows of step buttons either side of the value.
 *
 * A chest has no text field, so a number is entered by pressing steps - the left
 * half of a row decreases, the right half increases - or by typing it in chat.
 * This is the Paper {@code NumberSelectorGui} at the same slots, so the muscle
 * memory carries over between backends.
 *
 * Cancelling and submitting both close the screen, and only one of them tells the
 * caller. That is why every exit goes through {@link #finish}: a close the player
 * did themselves has to reach {@code onCancel}, and a close this screen did on
 * its way somewhere else must not.
 */
public final class NumberSelectorScreen {
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
	private static final int GUI_ROWS = 6;
	private static final int ICON_STACK_CAP = 99;

	private final LunaMenuHost menuHost;
	private final ChatPrompts chatPrompts;

	public NumberSelectorScreen(ChatPrompts chatPrompts) {
		this.menuHost = new LunaMenuHost(GUI_ROWS);
		this.chatPrompts = chatPrompts;
	}

	public void open(ServerPlayer player, Request request) {
		render(player, request, clamp(request.initialValue(), request.minValue(), request.maxValue()));
	}

	public void forget(UUID playerId) {
		menuHost.forget(playerId);
		chatPrompts.cancel(playerId);
	}

	public void closeAll() {
		menuHost.closeAll();
	}

	private void render(ServerPlayer player, Request request, double rawValue) {
		double value = clamp(rawValue, request.minValue(), request.maxValue());

		menuHost.open(player, titleWithValue(request, value), menu -> draw(player, menu, request, value));
	}

	private void draw(ServerPlayer player, LunaChestMenuBase menu, Request request, double value) {
		menu.clearTopSlots();
		fillFooter(menu);

		menu.setDecoration(VALUE_SLOT, valueItem(request, value));
		menu.setDecoration(INFO_SLOT, infoItem(request, value));

		drawSteps(player, menu, request, value, NORMAL_STEPS, NORMAL_DEC_SLOTS, NORMAL_INC_SLOTS, false);
		drawSteps(player, menu, request, value, STACK_STEPS, STACK_DEC_SLOTS, STACK_INC_SLOTS, true);

		menu.setTopSlot(CONFIRM_SLOT, LunaItems.of("lime_concrete", "<green>✔ Xác nhận", List.of(
			line(LunaPalette.SUCCESS_500, "Lưu giá trị hiện tại"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		)), () -> finish(player, () -> request.onSubmit().accept(player, normalizeOutput(value, request.integerMode()))));

		menu.setTopSlot(MANUAL_SLOT, LunaItems.of("name_tag", "<aqua>✎ Nhập thủ công", List.of(
			line(LunaPalette.INFO_500, "Nhập số trên chat"),
			line(LunaPalette.NEUTRAL_100, "Gõ <white>huy</white> để quay lại")
		)), () -> beginManualInput(player, request, value));

		menu.setTopSlot(CANCEL_SLOT, LunaItems.of("barrier", "<red>✖ Hủy", List.of(
			line(LunaPalette.DANGER_500, "Đóng mà không lưu")
		)), () -> finish(player, () -> request.onCancel().accept(player)));
	}

	private void drawSteps(
		ServerPlayer player,
		LunaChestMenuBase menu,
		Request request,
		double value,
		int[] steps,
		int[] decreaseSlots,
		int[] increaseSlots,
		boolean stackArea
	) {
		for (int index = 0; index < steps.length; index++) {
			int step = steps[index];

			menu.setTopSlot(
				decreaseSlots[index],
				stepItem(step, false, stackArea, value, request),
				() -> render(player, request, value - step)
			);

			menu.setTopSlot(
				increaseSlots[index],
				stepItem(step, true, stackArea, value, request),
				() -> render(player, request, value + step)
			);
		}
	}

	/**
	 * Leave the screen for good.
	 *
	 * The menu is forgotten before the container is closed, so the close callback
	 * finds nothing to report and whatever the caller opens next is not treated as
	 * a cancellation of this one.
	 */
	private void finish(ServerPlayer player, Runnable next) {
		menuHost.forget(player.getUUID());
		player.closeContainer();
		next.run();
	}

	private void beginManualInput(ServerPlayer player, Request request, double value) {
		menuHost.forget(player.getUUID());
		player.closeContainer();
		player.sendSystemMessage(LunaTextComponents.mini("<aqua>✦ Nhập số mới trên chat. Gõ <white>huy</white> để quay lại.</aqua>"));

		chatPrompts.await(player, input -> {
			if (isCancelWord(input)) {
				render(player, request, value);
				return;
			}

			try {
				render(player, request, parseNumber(input, request.integerMode()));
			} catch (NumberFormatException exception) {
				player.sendSystemMessage(LunaTextComponents.mini("<red>❌ Giá trị không hợp lệ. Hãy nhập số.</red>"));
				render(player, request, value);
			}
		});
	}

	private static boolean isCancelWord(String input) {
		return input == null || input.isBlank() || input.equalsIgnoreCase("huy") || input.equalsIgnoreCase("cancel");
	}

	private net.minecraft.world.item.ItemStack valueItem(Request request, double value) {
		List<String> lore = new ArrayList<>(List.of(
			line(LunaPalette.INFO_500, "ℹ Giá trị hiện tại"),
			line(LunaPalette.NEUTRAL_100, "<white>" + formatValue(request, value) + "</white>"),
			" ",
			line(LunaPalette.INFO_300, "⌚ Quy đổi stack"),
			line(LunaPalette.NEUTRAL_100, "<white>" + stackSummary(value, request.integerMode()) + "</white>"),
			" ",
			line(LunaPalette.WARNING_500, "↔ Khoảng cho phép"),
			line(LunaPalette.NEUTRAL_100, "Min: <white>" + formatValue(request, request.minValue()) + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Max: <white>" + formatValue(request, request.maxValue()) + "</white>")
		));

		int count = 1;

		if (request.integerMode()) {
			long whole = Math.round(normalizeOutput(value, true));
			count = (int) Math.max(1L, Math.min(ICON_STACK_CAP, whole));

			if (whole > ICON_STACK_CAP) {
				lore.add(" ");
				lore.add(line(LunaPalette.WARNING_500, "⚠ Icon chỉ đến " + ICON_STACK_CAP));
				lore.add(line(LunaPalette.NEUTRAL_100, "Số thật vẫn chính xác"));
			}
		}

		return LunaItems.of(request.displayMaterial(), "<aqua>◆ " + request.label(), lore, null, count);
	}

	private net.minecraft.world.item.ItemStack infoItem(Request request, double value) {
		return LunaItems.of("book", "<yellow>⌚ Hướng dẫn", List.of(
			line(LunaPalette.WARNING_500, "Nửa trái: giảm theo bước"),
			line(LunaPalette.WARNING_500, "Nửa phải: tăng theo bước"),
			line(LunaPalette.NEUTRAL_100, "Bước thường: <white>1..500</white>"),
			line(LunaPalette.NEUTRAL_100, "Bước stack: <white>1..128</white>"),
			line(LunaPalette.NEUTRAL_100, "Kiểu số: <white>" + (request.integerMode() ? "Số nguyên" : "Số thập phân") + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		));
	}

	private net.minecraft.world.item.ItemStack stepItem(int amount, boolean increase, boolean stackArea, double currentValue, Request request) {
		String material;

		if (stackArea) {
			material = increase ? "lime_stained_glass_pane" : "pink_stained_glass_pane";
		} else {
			material = increase ? "green_stained_glass_pane" : "red_stained_glass_pane";
		}

		double nextValue = clamp(increase ? currentValue + amount : currentValue - amount, request.minValue(), request.maxValue());
		String sign = increase ? "+" : "-";

		return LunaItems.of(material, "<white>" + sign + "</white> <yellow>" + amount + "</yellow>", List.of(
			line(increase ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500, (increase ? "Tăng" : "Giảm") + " <white>" + amount + "</white>"),
			line(LunaPalette.INFO_300, "Sau khi áp dụng: <white>" + formatValue(request, nextValue) + "</white>"),
			line(LunaPalette.INFO_300, "Stack: <white>" + stackSummary(nextValue, request.integerMode()) + "</white>")
		));
	}

	private void fillFooter(LunaChestMenuBase menu) {
		for (int slot = FOOTER_START; slot <= FOOTER_END; slot++) {
			menu.setDecoration(slot, LunaItems.of("black_stained_glass_pane", "<color:#374151> </color>", List.of()));
		}
	}

	private String line(String color, String text) {
		return "<color:" + color + ">" + text + "</color>";
	}

	private String stackSummary(double value, boolean integerMode) {
		if (!integerMode) {
			return "n/a";
		}

		long whole = Math.max(0L, Math.round(normalizeOutput(value, true)));
		return (whole / 64L) + " stack + " + (whole % 64L);
	}

	private double normalizeOutput(double value, boolean integerMode) {
		return integerMode ? Math.rint(value) : value;
	}

	private String formatValue(Request request, double value) {
		String text = request.numberDisplayFormatter().apply(normalizeOutput(value, request.integerMode()));

		if (request.unit() == null || request.unit().isBlank()) {
			return text;
		}

		return text + " " + request.unit();
	}

	private Component titleWithValue(Request request, double value) {
		return LunaTextComponents.mini(
			request.title()
				+ " <color:" + LunaPalette.NEUTRAL_500 + ">•</color> <color:" + LunaPalette.NEUTRAL_700 + ">"
				+ formatValue(request, value)
				+ "</color>"
		);
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

	/**
	 * What to ask for and what to do with the answer.
	 *
	 * @param title MiniMessage, without the value; the screen appends that itself
	 * @param label the name of the thing being counted, shown on the value icon
	 * @param unit appended after every formatted number, or blank for none
	 * @param displayMaterial the item drawn as the value icon
	 * @param integerMode whether the answer is whole; also drives the stack summary
	 */
	public record Request(
		String title,
		String label,
		String unit,
		String displayMaterial,
		double initialValue,
		double minValue,
		double maxValue,
		boolean integerMode,
		Function<Double, String> numberDisplayFormatter,
		BiConsumer<ServerPlayer, Double> onSubmit,
		Consumer<ServerPlayer> onCancel
	) {
		public static Request of(String title, String label, BiConsumer<ServerPlayer, Double> onSubmit, Consumer<ServerPlayer> onCancel) {
			return new Request(
				title,
				label,
				"",
				"paper",
				0D,
				0D,
				4096D,
				true,
				value -> String.format(Locale.ROOT, "%d", Math.round(value)),
				onSubmit,
				onCancel
			);
		}

		public Request withUnit(String value) {
			return new Request(title, label, value, displayMaterial, initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, onSubmit, onCancel);
		}

		public Request withDisplayMaterial(String value) {
			return new Request(title, label, unit, value, initialValue, minValue, maxValue, integerMode, numberDisplayFormatter, onSubmit, onCancel);
		}

		public Request withInitialValue(double value) {
			return new Request(title, label, unit, displayMaterial, value, minValue, maxValue, integerMode, numberDisplayFormatter, onSubmit, onCancel);
		}

		public Request withRange(double min, double max) {
			return new Request(title, label, unit, displayMaterial, initialValue, min, max, integerMode, numberDisplayFormatter, onSubmit, onCancel);
		}

		public Request withIntegerMode(boolean value) {
			return new Request(title, label, unit, displayMaterial, initialValue, minValue, maxValue, value, numberDisplayFormatter, onSubmit, onCancel);
		}

		public Request withNumberDisplayFormatter(Function<Double, String> value) {
			return new Request(title, label, unit, displayMaterial, initialValue, minValue, maxValue, integerMode, value, onSubmit, onCancel);
		}
	}
}
