package dev.belikhun.luna.core.api.help;

import org.bukkit.Material;

public record HelpCategory(
	String id,
	String plugin,
	String title,
	Material material,
	String description
) {
	public HelpCategory {
		id = normalizeId(id);
		plugin = plugin == null ? "Unknown" : plugin.trim();
		title = title == null || title.isBlank() ? plugin : title.trim();
		material = material == null ? Material.CHEST : material;
		description = description == null ? "" : description.trim();
	}

	private static String normalizeId(String value) {
		if (value == null || value.isBlank()) {
			return "uncategorized";
		}

		return value.trim().toLowerCase().replace(" ", "-");
	}
}
