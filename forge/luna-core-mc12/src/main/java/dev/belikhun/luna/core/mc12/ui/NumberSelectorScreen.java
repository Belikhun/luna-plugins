package dev.belikhun.luna.core.mc12.ui;

import dev.belikhun.luna.core.mc12.text.LunaTextComponents;
import dev.belikhun.luna.legacy.string.Strings;
import dev.belikhun.luna.legacy.ui.LunaPalette;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * "How many?" as a screen: two blocks of step buttons either side of the value.
 *
 * A chest has no text field, so a number is entered by pressing steps - the left
 * half of a row decreases, the right half increases - or by typing it in chat.
 * The slots are the ones Paper and the loaders use, so the muscle memory carries
 * over between backends.
 *
 * Cancelling and submitting both close the screen, and only one of them tells the
 * caller. That is why every exit goes through {@link #finish}: a close the player
 * did themselves has to reach the cancel callback, and a close this screen did on
 * its way somewhere else must not.
 *
 * It lives in the core rather than in the shop because nothing about it is a
 * shop: any module needing a number asks for one the same way.
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

	/** A stack of 100 draws as 99; the real number is never the icon's count. */
	private static final int ICON_STACK_CAP = 99;

	private final LunaMenuHost menuHost;
	private final LegacyChatPrompts chatPrompts;

	public NumberSelectorScreen(LegacyChatPrompts chatPrompts) {
		this.menuHost = new LunaMenuHost(GUI_ROWS);
		this.chatPrompts = chatPrompts;
	}

	public void open(EntityPlayerMP player, Request request) {
		render(player, request, clamp(request.initialValue(), request.minValue(), request.maxValue()));
	}

	public void forget(UUID playerId) {
		menuHost.forget(playerId);
		chatPrompts.cancel(playerId);
	}

	public void closeAll() {
		menuHost.closeAll();
	}

	private void render(final EntityPlayerMP player, final Request request, double rawValue) {
		final double value = clamp(rawValue, request.minValue(), request.maxValue());

		menuHost.open(player, LunaTextComponents.mini(titleWithValue(request, value)), menu -> draw(player, menu, request, value));
	}

	private void draw(final EntityPlayerMP player, LunaChestMenu menu, final Request request, final double value) {
		menu.clearTopSlots();
		fillFooter(menu);

		menu.setDecoration(VALUE_SLOT, valueItem(request, value));
		menu.setDecoration(INFO_SLOT, infoItem(request, value));

		drawSteps(player, menu, request, value, NORMAL_STEPS, NORMAL_DEC_SLOTS, NORMAL_INC_SLOTS, false);
		drawSteps(player, menu, request, value, STACK_STEPS, STACK_DEC_SLOTS, STACK_INC_SLOTS, true);

		menu.setTopSlot(CONFIRM_SLOT, LunaItems.of("lime_dye", "<green>✔ Xác nhận", Arrays.asList(
			line(LunaPalette.SUCCESS_500, "Lưu giá trị hiện tại"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		)), () -> finish(player, () -> request.onSubmit().accept(player, Double.valueOf(normalizeOutput(value, request.integerMode())))));

		menu.setTopSlot(MANUAL_SLOT, LunaItems.of("name_tag", "<aqua>✎ Nhập thủ công", Arrays.asList(
			line(LunaPalette.INFO_500, "Nhập số trên chat"),
			line(LunaPalette.NEUTRAL_100, "Gõ <white>huy</white> để quay lại")
		)), () -> beginManualInput(player, request, value));

		menu.setTopSlot(CANCEL_SLOT, LunaItems.of("barrier", "<red>✖ Hủy", Collections.singletonList(
			line(LunaPalette.DANGER_500, "Đóng mà không lưu")
		)), () -> finish(player, () -> request.onCancel().accept(player)));
	}

	private void drawSteps(
		final EntityPlayerMP player,
		LunaChestMenu menu,
		final Request request,
		final double value,
		int[] steps,
		int[] decreaseSlots,
		int[] increaseSlots,
		boolean stackArea
	) {
		for (int index = 0; index < steps.length; index += 1) {
			final int step = steps[index];

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
	 * The menu is forgotten before the window is closed, so the close callback finds
	 * nothing to report and whatever the caller opens next is not mistaken for a
	 * cancellation of this one.
	 */
	private void finish(EntityPlayerMP player, Runnable next) {
		menuHost.forget(player.getUniqueID());
		menuHost.close(player);
		next.run();
	}

	private void beginManualInput(final EntityPlayerMP player, final Request request, final double value) {
		menuHost.forget(player.getUniqueID());
		menuHost.close(player);
		player.sendMessage(LunaTextComponents.mini("<aqua>✦ Nhập số mới trên chat. Gõ <white>huy</white> để quay lại.</aqua>"));

		chatPrompts.await(player, input -> {
			if (isCancelWord(input)) {
				render(player, request, value);

				return;
			}

			try {
				render(player, request, parseNumber(input, request.integerMode()));
			} catch (NumberFormatException invalid) {
				player.sendMessage(LunaTextComponents.mini("<red>❌ Giá trị không hợp lệ. Hãy nhập số.</red>"));
				render(player, request, value);
			}
		});
	}

	private static boolean isCancelWord(String input) {
		return Strings.isBlank(input) || "huy".equalsIgnoreCase(input.trim()) || "cancel".equalsIgnoreCase(input.trim());
	}

	private ItemStack valueItem(Request request, double value) {
		List<String> lore = new ArrayList<String>(Arrays.asList(
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

	private ItemStack infoItem(Request request, double value) {
		return LunaItems.of("book", "<yellow>⌚ Hướng dẫn", Arrays.asList(
			line(LunaPalette.WARNING_500, "Nửa trái: giảm theo bước"),
			line(LunaPalette.WARNING_500, "Nửa phải: tăng theo bước"),
			line(LunaPalette.NEUTRAL_100, "Bước thường: <white>1..500</white>"),
			line(LunaPalette.NEUTRAL_100, "Bước stack: <white>1..128</white>"),
			line(LunaPalette.NEUTRAL_100, "Kiểu số: <white>" + (request.integerMode() ? "Số nguyên" : "Số thập phân") + "</white>"),
			line(LunaPalette.NEUTRAL_100, "Giá trị: <white>" + formatValue(request, value) + "</white>")
		));
	}

	private ItemStack stepItem(int amount, boolean increase, boolean stackArea, double currentValue, Request request) {
		String material;

		if (stackArea) {
			material = increase ? "lime_stained_glass_pane" : "pink_stained_glass_pane";
		} else {
			material = increase ? "green_stained_glass_pane" : "red_stained_glass_pane";
		}

		double nextValue = clamp(increase ? currentValue + amount : currentValue - amount, request.minValue(), request.maxValue());
		String sign = increase ? "+" : "-";

		return LunaItems.of(material, "<white>" + sign + "</white> <yellow>" + amount + "</yellow>", Arrays.asList(
			line(increase ? LunaPalette.SUCCESS_500 : LunaPalette.DANGER_500, (increase ? "Tăng" : "Giảm") + " <white>" + amount + "</white>"),
			line(LunaPalette.INFO_300, "Sau khi áp dụng: <white>" + formatValue(request, nextValue) + "</white>"),
			line(LunaPalette.INFO_300, "Stack: <white>" + stackSummary(nextValue, request.integerMode()) + "</white>")
		));
	}

	private void fillFooter(LunaChestMenu menu) {
		for (int slot = FOOTER_START; slot <= FOOTER_END; slot += 1) {
			menu.setDecoration(slot, LunaItems.of("black_stained_glass_pane", "<color:" + LunaPalette.NEUTRAL_700 + "> </color>", Collections.<String>emptyList()));
		}
	}

	private String line(String colour, String text) {
		return "<color:" + colour + ">" + text + "</color>";
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
		String text = request.formatter().format(normalizeOutput(value, request.integerMode()));

		if (Strings.isBlank(request.unit())) {
			return text;
		}

		return text + " " + request.unit();
	}

	private String titleWithValue(Request request, double value) {
		return request.title()
			+ " <color:" + LunaPalette.NEUTRAL_500 + ">•</color> <color:" + LunaPalette.NEUTRAL_700 + ">"
			+ formatValue(request, value)
			+ "</color>";
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

	/** How a number reads on screen; the caller owns the wording of its own units. */
	public interface ValueFormatter {
		String format(double value);
	}

	/** What the screen does with the number once the player commits to it. */
	public interface SubmitHandler {
		void accept(EntityPlayerMP player, Double value);
	}

	/**
	 * What to ask for and what to do with the answer.
	 *
	 * A plain class rather than a record: the legacy line is Java 8, and this is the
	 * shape the other backends express as one.
	 */
	public static final class Request {
		private final String title;
		private final String label;
		private final String unit;
		private final String displayMaterial;
		private final double initialValue;
		private final double minValue;
		private final double maxValue;
		private final boolean integerMode;
		private final ValueFormatter formatter;
		private final SubmitHandler onSubmit;
		private final Consumer<EntityPlayerMP> onCancel;

		private Request(
			String title,
			String label,
			String unit,
			String displayMaterial,
			double initialValue,
			double minValue,
			double maxValue,
			boolean integerMode,
			ValueFormatter formatter,
			SubmitHandler onSubmit,
			Consumer<EntityPlayerMP> onCancel
		) {
			this.title = title;
			this.label = label;
			this.unit = unit;
			this.displayMaterial = displayMaterial;
			this.initialValue = initialValue;
			this.minValue = minValue;
			this.maxValue = maxValue;
			this.integerMode = integerMode;
			this.formatter = formatter;
			this.onSubmit = onSubmit;
			this.onCancel = onCancel;
		}

		/** A whole-number request from 0 to 4096, which most callers then narrow. */
		public static Request of(String title, String label, SubmitHandler onSubmit, Consumer<EntityPlayerMP> onCancel) {
			return new Request(
				title,
				label,
				"",
				"paper",
				0D,
				0D,
				4096D,
				true,
				value -> String.format(Locale.ROOT, "%d", Long.valueOf(Math.round(value))),
				onSubmit,
				onCancel
			);
		}

		public String title() {
			return title;
		}

		public String label() {
			return label;
		}

		public String unit() {
			return unit;
		}

		public String displayMaterial() {
			return displayMaterial;
		}

		public double initialValue() {
			return initialValue;
		}

		public double minValue() {
			return minValue;
		}

		public double maxValue() {
			return maxValue;
		}

		public boolean integerMode() {
			return integerMode;
		}

		public ValueFormatter formatter() {
			return formatter;
		}

		public SubmitHandler onSubmit() {
			return onSubmit;
		}

		public Consumer<EntityPlayerMP> onCancel() {
			return onCancel;
		}

		public Request withUnit(String value) {
			return new Request(title, label, value, displayMaterial, initialValue, minValue, maxValue, integerMode, formatter, onSubmit, onCancel);
		}

		public Request withDisplayMaterial(String value) {
			return new Request(title, label, unit, value, initialValue, minValue, maxValue, integerMode, formatter, onSubmit, onCancel);
		}

		public Request withInitialValue(double value) {
			return new Request(title, label, unit, displayMaterial, value, minValue, maxValue, integerMode, formatter, onSubmit, onCancel);
		}

		public Request withRange(double min, double max) {
			return new Request(title, label, unit, displayMaterial, initialValue, min, max, integerMode, formatter, onSubmit, onCancel);
		}

		public Request withIntegerMode(boolean value) {
			return new Request(title, label, unit, displayMaterial, initialValue, minValue, maxValue, value, formatter, onSubmit, onCancel);
		}

		public Request withFormatter(ValueFormatter value) {
			return new Request(title, label, unit, displayMaterial, initialValue, minValue, maxValue, integerMode, value, onSubmit, onCancel);
		}
	}
}
