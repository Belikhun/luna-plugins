package dev.belikhun.luna.legacy.ui;

import dev.belikhun.luna.legacy.string.Strings;

import java.util.Locale;

/**
 * Which mouse button pressed a menu button, in terms both game lines can state.
 *
 * A luna screen never moves items, so the only thing a click carries is intent:
 * the shop reads left as buy and right as sell, and shift-right as "sell all".
 * The game spells the kind {@code ClickType} through 1.21 and
 * {@code ContainerInput} from 26.x - two enums with the same constants - so the
 * per-line {@link LunaChestMenu} passes the constant's name and the mapping
 * happens once, here, instead of once per screen.
 */
public final class LunaClick {
	private final int slot;
	private final int button;
	private final Kind kind;

	public LunaClick(int slot, int button, Kind kind) {
		this.slot = slot;
		this.button = button;
		this.kind = kind;
	}

	public int slot() {
		return slot;
	}

	public int button() {
		return button;
	}

	public Kind kind() {
		return kind;
	}

	public enum Kind {
		PICKUP,
		QUICK_MOVE,
		SWAP,
		CLONE,
		THROW,
		QUICK_CRAFT,
		PICKUP_ALL,
		UNKNOWN;

		public static Kind fromName(String name) {
			if (Strings.isBlank(name)) {
				return UNKNOWN;
			}

			try {
				return valueOf(name.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				return UNKNOWN;
			}
		}
	}

	public static LunaClick of(int slot, int button, String kindName) {
		return new LunaClick(slot, button, Kind.fromName(kindName));
	}

	public boolean isLeft() {
		return button == 0;
	}

	public boolean isRight() {
		return button == 1;
	}

	/**
	 * Whether the player was holding shift.
	 *
	 * The client sends a shift-click as QUICK_MOVE and keeps the button number, so
	 * shift-right is {@code QUICK_MOVE} with button 1 - which is exactly how the
	 * Paper shop tells "sell one" from "sell the whole stack" apart.
	 */
	public boolean isShift() {
		return kind == Kind.QUICK_MOVE;
	}

	public boolean isMiddle() {
		return kind == Kind.CLONE;
	}

	public boolean isDrop() {
		return kind == Kind.THROW;
	}
}
