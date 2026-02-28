package dev.belikhun.luna.core.api.help;

import org.bukkit.command.CommandSender;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public final class HelpRegistry {
	private final List<HelpEntry> entries;
	private final Map<String, HelpCategory> categories;

	public HelpRegistry() {
		this.entries = new CopyOnWriteArrayList<>();
		this.categories = new LinkedHashMap<>();
	}

	public void registerCategory(HelpCategory category) {
		categories.put(category.id(), category);
	}

	public void register(HelpEntry entry) {
		entries.removeIf(existing -> existing.command().equalsIgnoreCase(entry.command()));
		entries.add(entry);
		categories.putIfAbsent(entry.categoryId(), new HelpCategory(
			entry.categoryId(),
			entry.plugin(),
			entry.plugin(),
			Material.CHEST,
			"Danh mục lệnh của " + entry.plugin()
		));
	}

	public void unregisterByPlugin(String pluginName) {
		entries.removeIf(entry -> entry.plugin().equalsIgnoreCase(pluginName));
		categories.entrySet().removeIf(entry -> entry.getValue().plugin().equalsIgnoreCase(pluginName));
	}

	public List<HelpEntry> visibleEntries(CommandSender sender) {
		return entries.stream()
			.filter(entry -> entry.canUse(sender))
			.sorted(Comparator.comparing(entry -> entry.command().toLowerCase(Locale.ROOT)))
			.collect(Collectors.toCollection(ArrayList::new));
	}

	public List<HelpCategory> visibleCategories(CommandSender sender) {
		return categories.values().stream()
			.filter(category -> countVisibleEntries(sender, category.id()) > 0)
			.sorted(Comparator.comparing(category -> category.title().toLowerCase(Locale.ROOT)))
			.collect(Collectors.toCollection(ArrayList::new));
	}

	public Optional<HelpCategory> category(String id) {
		if (id == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(categories.get(id.trim().toLowerCase(Locale.ROOT)));
	}

	public Optional<HelpCategory> findVisibleCategory(CommandSender sender, String keyword) {
		if (keyword == null) {
			return Optional.empty();
		}

		String normalized = keyword.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return Optional.empty();
		}

		return visibleCategories(sender).stream()
			.filter(category ->
				category.id().equalsIgnoreCase(normalized)
					|| category.plugin().equalsIgnoreCase(normalized)
					|| category.title().equalsIgnoreCase(normalized)
			)
			.findFirst();
	}

	public Optional<HelpEntry> findVisibleEntry(CommandSender sender, String keyword) {
		if (keyword == null) {
			return Optional.empty();
		}

		String normalized = keyword.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			return Optional.empty();
		}

		String withSlash = normalized.startsWith("/") ? normalized : "/" + normalized;
		return visibleEntries(sender).stream()
			.filter(entry -> entry.command().equalsIgnoreCase(normalized) || entry.command().equalsIgnoreCase(withSlash))
			.findFirst();
	}

	public List<HelpEntry> visibleEntriesByCategory(CommandSender sender, String categoryId) {
		String normalized = categoryId == null ? "" : categoryId.trim().toLowerCase(Locale.ROOT);
		return visibleEntries(sender).stream()
			.filter(entry -> entry.categoryId().equalsIgnoreCase(normalized))
			.collect(Collectors.toCollection(ArrayList::new));
	}

	public List<HelpEntry> search(CommandSender sender, String keyword) {
		String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
		if (query.isBlank()) {
			return visibleEntries(sender);
		}

		return visibleEntries(sender).stream()
			.filter(entry -> matches(entry, query))
			.collect(Collectors.toCollection(ArrayList::new));
	}

	public int countVisibleEntries(CommandSender sender, String categoryId) {
		return visibleEntriesByCategory(sender, categoryId).size();
	}

	private boolean matches(HelpEntry entry, String query) {
		if (entry.command().toLowerCase(Locale.ROOT).contains(query)) {
			return true;
		}

		if (entry.description().toLowerCase(Locale.ROOT).contains(query)) {
			return true;
		}

		if (entry.plugin().toLowerCase(Locale.ROOT).contains(query)) {
			return true;
		}

		for (String example : entry.usageExamples()) {
			if (example.toLowerCase(Locale.ROOT).contains(query)) {
				return true;
			}
		}

		for (HelpArgument argument : entry.arguments()) {
			if (argument.name().toLowerCase(Locale.ROOT).contains(query)) {
				return true;
			}
			if (argument.description().toLowerCase(Locale.ROOT).contains(query)) {
				return true;
			}
			for (String value : argument.enumValues()) {
				if (value.toLowerCase(Locale.ROOT).contains(query)) {
					return true;
				}
			}
		}

		return false;
	}
}
