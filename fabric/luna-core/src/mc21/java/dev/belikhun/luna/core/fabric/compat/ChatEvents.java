package dev.belikhun.luna.core.fabric.compat;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chat click and hover events, as the 1.20-1.21 line spells them.
 *
 * This is the only place the two supported game lines disagree in a way the
 * shared sources cannot paper over, which is why it is the only file that exists
 * twice: 1.21.5 turned both event types from concrete classes into sealed
 * interfaces with a record per action, so there is no expression that compiles
 * against both. Everything else in this mod is one set of sources compiled twice.
 *
 * The 26.x copy lives in luna-core-mc26-fabric under the same name, so the
 * caller links one class either way and never learns which line it is on.
 *
 * Nothing here is guarded. On 1.21.5 and up the constructors below are gone and
 * the call throws {@link NoSuchMethodError} at the instruction - which is what
 * the caller's {@link Guarded} wrapper is for, and why keeping these two calls
 * in a class of their own is worth doing: the failure cannot reach anything else.
 */
public final class ChatEvents {
	private ChatEvents() {
	}

	/** Make a line run a command when clicked, showing {@code tooltip} on hover. */
	public static void decorate(MutableComponent line, String command, Component tooltip) {
		line.setStyle(line.getStyle()
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, tooltip)));
	}
}
