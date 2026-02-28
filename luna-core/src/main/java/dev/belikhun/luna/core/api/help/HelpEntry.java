package dev.belikhun.luna.core.api.help;

public record HelpEntry(
	String plugin,
	String command,
	String usage,
	String description,
	String permission
) {
	public boolean canUse(org.bukkit.command.CommandSender sender) {
		return permission == null || permission.isBlank() || sender.hasPermission(permission);
	}
}
