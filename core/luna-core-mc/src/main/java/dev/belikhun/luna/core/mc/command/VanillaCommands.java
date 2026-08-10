package dev.belikhun.luna.core.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Taking a command name off the dispatcher so a luna command can own it.
 *
 * Registering a literal the game already has does not replace it: brigadier
 * *merges* the two, keeping the existing node and adding the new children
 * beside the old ones. Parsing then tries the children in the order they were
 * added, so vanilla's `/msg &lt;targets&gt; &lt;message&gt;` still matches first and the
 * luna command never runs - which is what makes `/msg` on a mod loader behave
 * like vanilla while the paper build uses luna's own. Bukkit has no such
 * problem: a plugin command simply wins.
 *
 * The child maps are private with no removal API, so this reaches them by
 * reflection. They are plain brigadier classes rather than anything the JDK
 * guards, and a failure is not fatal: the command still registers, it just
 * loses to vanilla, so callers report rather than throw.
 */
public final class VanillaCommands {
	private static final String[] CHILD_MAPS = { "children", "literals", "arguments" };

	private VanillaCommands() {
	}

	/**
	 * Remove these command names from the dispatcher's root.
	 *
	 * @return whether every name given was removed (a name the game does not
	 *         have counts as removed; there is nothing left to shadow)
	 */
	public static boolean remove(CommandDispatcher<?> dispatcher, String... names) {
		if (dispatcher == null || names == null) {
			return false;
		}

		CommandNode<?> root = dispatcher.getRoot();
		boolean removedEverything = true;

		for (String name : names) {
			removedEverything &= removeFrom(root, name);
		}

		return removedEverything;
	}

	private static boolean removeFrom(CommandNode<?> root, String name) {
		if (name == null || name.isBlank()) {
			return true;
		}

		boolean removed = true;

		for (String mapName : CHILD_MAPS) {
			try {
				Field field = CommandNode.class.getDeclaredField(mapName);
				field.setAccessible(true);
				Object value = field.get(root);

				if (value instanceof Map<?, ?> children) {
					children.remove(name);
				}
			} catch (ReflectiveOperationException | RuntimeException unreachable) {
				removed = false;
			}
		}

		return removed;
	}
}
