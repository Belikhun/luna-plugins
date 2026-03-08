package dev.belikhun.luna.core.api.help;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record HelpArgument(
	String name,
	boolean required,
	String description,
	List<String> enumValues,
	Material material
) {
	public HelpArgument {
		name = name == null ? "arg" : name.trim();
		description = description == null ? "" : description.trim();
		enumValues = enumValues == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(enumValues));
		material = material == null ? Material.PAPER : material;
	}

	public HelpArgument(String name, boolean required, String description, List<String> enumValues) {
		this(name, required, description, enumValues, Material.PAPER);
	}

	public String syntaxToken() {
		if (required) {
			return "<" + name + ">";
		}

		return "[" + name + "]";
	}
}

