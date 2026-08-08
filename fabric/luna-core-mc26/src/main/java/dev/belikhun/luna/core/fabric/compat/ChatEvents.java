package dev.belikhun.luna.core.fabric.compat;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chat click and hover events, as the 26.x line spells them.
 *
 * The 1.20-1.21 copy of this class lives in luna-core-fabric/src/mc21; see it for
 * why this one file exists twice while the rest of the mod is compiled twice from
 * one set of sources. 1.21.5 replaced both concrete event classes with sealed
 * interfaces, so from there on an event is a record naming its own action and the
 * separate Action argument is gone.
 *
 * Unlike its counterpart these calls cannot fail on any version this build
 * accepts, so the list is always clickable here.
 */
public final class ChatEvents {
	private ChatEvents() {
	}

	/** Make a line run a command when clicked, showing {@code tooltip} on hover. */
	public static void decorate(MutableComponent line, String command, Component tooltip) {
		line.setStyle(line.getStyle()
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(tooltip)));
	}
}
