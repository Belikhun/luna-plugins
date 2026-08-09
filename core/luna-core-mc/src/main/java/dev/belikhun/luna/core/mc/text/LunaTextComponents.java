package dev.belikhun.luna.core.mc.text;

import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * MiniMessage into a Minecraft component, without going through JSON.
 *
 * Paper and NeoForge round-trip Adventure components through the game's own
 * component codec, but that codec is exactly what the 1.20.5 component rewrite
 * replaced, and reaching it needs a registry-aware serialization context whose
 * shape has moved twice since. Walking the Adventure tree and rebuilding it with
 * {@code Component.literal} and {@code Style} instead touches only methods that
 * have been stable since 1.16, which is what lets one jar render text on every
 * version this mod supports.
 *
 * What is deliberately not carried across: click and hover events, fonts and
 * insertions. Those have all changed shape inside the supported range, and the
 * text this converts (menu titles, item names, status lines) never uses them.
 */
public final class LunaTextComponents {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	private LunaTextComponents() {
	}

	/** Render a MiniMessage string, with the italic default items carry undone. */
	public static net.minecraft.network.chat.Component mini(String miniMessage) {
		if (miniMessage == null || miniMessage.isBlank()) {
			return net.minecraft.network.chat.Component.empty();
		}

		return adventure(MINI_MESSAGE.deserialize("<!italic>" + miniMessage));
	}

	/** Convert an Adventure component tree into the game's own components. */
	public static net.minecraft.network.chat.Component adventure(net.kyori.adventure.text.Component component) {
		if (component == null) {
			return net.minecraft.network.chat.Component.empty();
		}

		MutableComponent converted = leaf(component);

		converted.setStyle(style(component.style()));

		for (net.kyori.adventure.text.Component child : component.children()) {
			converted.append(adventure(child));
		}

		return converted;
	}

	private static MutableComponent leaf(net.kyori.adventure.text.Component component) {
		if (component instanceof TextComponent text) {
			return net.minecraft.network.chat.Component.literal(text.content());
		}

		if (component instanceof TranslatableComponent translatable) {
			return net.minecraft.network.chat.Component.translatable(translatable.key());
		}

		// scores, selectors and keybinds have no server-side text to show; an
		// empty node keeps the surrounding styling and children intact
		return net.minecraft.network.chat.Component.empty();
	}

	private static Style style(net.kyori.adventure.text.format.Style source) {
		Style style = Style.EMPTY;
		TextColor color = source.color();

		if (color != null) {
			style = style.withColor(net.minecraft.network.chat.TextColor.fromRgb(color.value()));
		}

		style = decorate(style, source, TextDecoration.BOLD);
		style = decorate(style, source, TextDecoration.ITALIC);
		style = decorate(style, source, TextDecoration.UNDERLINED);
		style = decorate(style, source, TextDecoration.STRIKETHROUGH);
		style = decorate(style, source, TextDecoration.OBFUSCATED);

		return style;
	}

	/**
	 * Carry one decoration across, keeping the difference between "off" and
	 * "unset": an item name has to say italic=false explicitly, or the client
	 * italicises it again.
	 */
	private static Style decorate(Style style, net.kyori.adventure.text.format.Style source, TextDecoration decoration) {
		TextDecoration.State state = source.decoration(decoration);

		if (state == TextDecoration.State.NOT_SET) {
			return style;
		}

		Boolean value = state == TextDecoration.State.TRUE;

		return switch (decoration) {
			case BOLD -> style.withBold(value);
			case ITALIC -> style.withItalic(value);
			case UNDERLINED -> style.withUnderlined(value);
			case STRIKETHROUGH -> style.withStrikethrough(value);
			case OBFUSCATED -> style.withObfuscated(value);
		};
	}
}
