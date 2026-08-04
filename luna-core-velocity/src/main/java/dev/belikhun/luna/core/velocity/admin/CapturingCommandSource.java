package dev.belikhun.luna.core.velocity.admin;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A console-equivalent command source that records what a command replied.
 *
 * Running a command through the proxy console gives no way to read its output back;
 * this collects every component the command sends so the HTTP caller receives the
 * actual result instead of a bare "dispatched". Messages are flattened to plain
 * text — the console renders its own styling, and MiniMessage tags in a command's
 * reply would only be noise there.
 *
 * Permission checks always pass: the caller already authenticated with the
 * forwarding secret, which is the network's administrative credential.
 */
public final class CapturingCommandSource implements CommandSource {
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

	private final List<String> lines = new ArrayList<>();

	@Override
	public void sendMessage(Component message) {
		if (message == null) {
			return;
		}

		// Velocity's own commands reply with translatable components, which a real client
		// resolves locally. Nothing renders them for an HTTP caller, so without this the
		// console would receive raw keys like "velocity.command.glist-player-plural".
		String text = PLAIN.serialize(GlobalTranslator.render(message, Locale.US));

		synchronized (lines) {
			// A command may reply with a multi-line component; the console shows rows.
			for (String line : text.split("\n", -1)) {
				lines.add(line);
			}
		}
	}

	@Override
	public Tristate getPermissionValue(String permission) {
		return Tristate.TRUE;
	}

	/** Everything the command sent, in order. */
	public List<String> output() {
		synchronized (lines) {
			return Collections.unmodifiableList(new ArrayList<>(lines));
		}
	}
}
