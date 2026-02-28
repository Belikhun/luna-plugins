package dev.belikhun.luna.core.api.help;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class HelpRegistry {
	private final List<HelpEntry> entries;

	public HelpRegistry() {
		this.entries = new CopyOnWriteArrayList<>();
	}

	public void register(HelpEntry entry) {
		entries.removeIf(existing -> existing.command().equalsIgnoreCase(entry.command()));
		entries.add(entry);
	}

	public void unregisterByPlugin(String pluginName) {
		entries.removeIf(entry -> entry.plugin().equalsIgnoreCase(pluginName));
	}

	public List<HelpEntry> visibleEntries(CommandSender sender) {
		List<HelpEntry> visible = new ArrayList<>();
		for (HelpEntry entry : entries) {
			if (entry.canUse(sender)) {
				visible.add(entry);
			}
		}
		visible.sort(Comparator.comparing(entry -> entry.command().toLowerCase(Locale.ROOT)));
		return visible;
	}
}
