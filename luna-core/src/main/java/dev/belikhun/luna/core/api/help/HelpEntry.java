package dev.belikhun.luna.core.api.help;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record HelpEntry(
	String plugin,
	String categoryId,
	Material material,
	String command,
	String description,
	String permission,
	List<String> usageExamples,
	List<HelpArgument> arguments
) {
	public HelpEntry {
		plugin = plugin == null || plugin.isBlank() ? "Unknown" : plugin.trim();
		categoryId = categoryId == null || categoryId.isBlank() ? plugin.toLowerCase() : categoryId.trim().toLowerCase();
		material = material == null ? Material.BOOK : material;
		command = command == null ? "" : command.trim();
		description = description == null ? "" : description.trim();
		permission = permission == null ? "" : permission.trim();
		usageExamples = usageExamples == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(usageExamples));
		arguments = arguments == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(arguments));
	}

	public HelpEntry(String plugin, String categoryId, Material material, String command, String description, String permission) {
		this(plugin, categoryId, material, command, description, permission, List.of(), List.of());
	}

	public boolean canUse(org.bukkit.command.CommandSender sender) {
		return permission == null || permission.isBlank() || sender.hasPermission(permission);
	}

	public String syntaxLine() {
		StringBuilder syntax = new StringBuilder(command);
		for (HelpArgument argument : arguments) {
			syntax.append(" ").append(argument.syntaxToken());
		}
		return syntax.toString();
	}
}
