package dev.belikhun.luna.patpat;

import dev.belikhun.luna.core.api.string.CommandCompletions;
import dev.belikhun.luna.core.api.ui.LunaUi;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /patanim
 *   list [page]                      the library, with buttons to pick for yourself
 *   me                               what you have chosen
 *   set <in|out> <animation|none>    in: when someone pats you; out: when you pat someone
 * admin (lunapatpat.admin)
 *   player <name> <in|out> <animation|none>
 *   pair <from> <to> <animation|none>   when <from> pats <to>
 *   default <animation|none>            every other pat
 *   import modrinth <slug|url>
 *   import file <name.zip>              from plugins/LunaPatPat/import/
 *   remove <animation>
 *   info <animation>
 *   rules                               every choice on record
 *   build                               rebuild and resend the pack now
 *   reload
 */
final class PatAnimCommand implements BasicCommand {
	private static final String ADMIN = "lunapatpat.admin";
	private static final int PAGE = 8;

	private final LunaPatPat plugin;

	PatAnimCommand(LunaPatPat plugin) {
		this.plugin = plugin;
	}

	@Override
	public void execute(CommandSourceStack source, String[] args) {
		CommandSender sender = source.getSender();
		String sub = args.length == 0 ? (sender instanceof Player ? "gui" : "list") : args[0].toLowerCase(Locale.ROOT);
		boolean admin = sender.hasPermission(ADMIN);
		switch (sub) {
			case "gui", "menu" -> {
				if (sender instanceof Player p) {
					if (args.length > 1 && admin) {
						OfflinePlayer t = player(sender, args[1]);
						if (t != null) plugin.gui().openFor(p, t.getUniqueId(), name(t, args[1]));
					} else {
						plugin.gui().openMain(p);
					}
				} else {
					say(sender, "<red>❌ Chỉ người chơi mới mở được giao diện.</red>");
				}
			}
			case "list" -> list(sender, args.length > 1 ? parseInt(args[1], 1) : 1);
			case "me" -> me(sender);
			case "set" -> setOwn(sender, args);
			case "player" -> { if (requireAdmin(sender, admin)) setPlayer(sender, args); }
			case "pair" -> { if (requireAdmin(sender, admin)) setPair(sender, args); }
			case "default" -> { if (requireAdmin(sender, admin)) setDefault(sender, args); }
			case "import" -> { if (requireAdmin(sender, admin)) importPack(sender, args); }
			case "remove" -> { if (requireAdmin(sender, admin)) remove(sender, args); }
			case "info" -> info(sender, args);
			case "rules" -> { if (requireAdmin(sender, admin)) rules(sender); }
			case "build" -> {
				if (requireAdmin(sender, admin)) {
					say(sender, "<gray>Đang dựng lại gói PatPat…</gray>");
					Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.rebuild(sender));
				}
			}
			case "reload" -> {
				if (requireAdmin(sender, admin)) {
					try {
						plugin.reloadAll();
						say(sender, "<green>✔ Đã tải lại: " + plugin.library().all().size() + " hoạt ảnh.</green>");
					} catch (IOException e) {
						say(sender, "<red>❌ " + e.getMessage() + "</red>");
					}
				}
			}
			default -> help(sender, admin);
		}
	}

	// ---- player commands ------------------------------------------------------------------------

	private void list(CommandSender sender, int page) {
		List<Animation> all = new ArrayList<>(plugin.library().all());
		if (all.isEmpty()) {
			say(sender, "<gray>Chưa có hoạt ảnh nào trong thư viện.</gray>");
			return;
		}
		int pages = (all.size() + PAGE - 1) / PAGE;
		page = Math.max(1, Math.min(pages, page));
		say(sender, "<gold>─── Hoạt ảnh PatPat</gold> <gray>(" + page + "/" + pages + ")</gray>");
		boolean player = sender instanceof Player;
		for (Animation a : all.subList((page - 1) * PAGE, Math.min(all.size(), page * PAGE))) {
			Component line = Component.text("• ", NamedTextColor.DARK_GRAY)
				.append(Component.text(a.name(), NamedTextColor.WHITE)
					.hoverEvent(HoverEvent.showText(Component.text(a.id() + (a.hasSound() ? " · có âm thanh" : " · không âm thanh") + "\nnguồn: " + a.source()))))
				.append(Component.text("  "));
			if (player) {
				line = line
					.append(button("[khi được vỗ]", "/patanim set in " + a.id(), "Dùng khi người khác vỗ đầu bạn", NamedTextColor.LIGHT_PURPLE))
					.append(Component.text(" "))
					.append(button("[khi tôi vỗ]", "/patanim set out " + a.id(), "Dùng khi bạn vỗ đầu người khác", NamedTextColor.AQUA));
			}
			sender.sendMessage(line);
		}
		Component nav = Component.empty();
		if (page > 1) nav = nav.append(button("« trước", "/patanim list " + (page - 1), "Trang trước", NamedTextColor.GRAY)).append(Component.text("  "));
		if (page < pages) nav = nav.append(button("sau »", "/patanim list " + (page + 1), "Trang sau", NamedTextColor.GRAY));
		if (player) nav = nav.append(Component.text("   ")).append(button("[bỏ chọn]", "/patanim me", "Xem và bỏ lựa chọn của bạn", NamedTextColor.RED));
		sender.sendMessage(nav);
	}

	private void me(CommandSender sender) {
		if (!(sender instanceof Player p)) {
			say(sender, "<red>❌ Chỉ người chơi mới dùng được.</red>");
			return;
		}
		Selections.PlayerChoice c = plugin.selections().get(p.getUniqueId());
		sender.sendMessage(Component.text("Khi được vỗ: ", NamedTextColor.GRAY).append(choice(c == null ? null : c.incoming))
			.append(Component.text("  ")).append(button("[bỏ]", "/patanim set in none", "Về mặc định", NamedTextColor.RED)));
		sender.sendMessage(Component.text("Khi bạn vỗ: ", NamedTextColor.GRAY).append(choice(c == null ? null : c.outgoing))
			.append(Component.text("  ")).append(button("[bỏ]", "/patanim set out none", "Về mặc định", NamedTextColor.RED)));
	}

	private void setOwn(CommandSender sender, String[] args) {
		if (!(sender instanceof Player p)) {
			say(sender, "<red>❌ Chỉ người chơi mới dùng được; quản trị viên dùng /patanim player.</red>");
			return;
		}
		if (!sender.hasPermission("lunapatpat.use")) {
			say(sender, "<red>❌ Bạn không có quyền.</red>");
			return;
		}
		if (args.length < 3) {
			say(sender, "<yellow>/patanim set <in|out> <hoạt ảnh|none></yellow>");
			return;
		}
		Selections.Kind kind = kind(args[1]);
		String anim = animation(sender, args[2]);
		if (kind == null || anim == null && !isNone(args[2])) {
			return;
		}
		plugin.selections().set(p.getUniqueId(), p.getName(), kind, anim);
		plugin.saveSelections();
		say(sender, "<green>✔ " + (kind == Selections.Kind.INCOMING ? "Khi được vỗ" : "Khi bạn vỗ") + ": " + label(anim) + ".</green> <gray>Gói sẽ được gửi lại sau vài giây.</gray>");
		plugin.scheduleRebuild(sender);
	}

	// ---- admin commands -------------------------------------------------------------------------

	private void setPlayer(CommandSender sender, String[] args) {
		if (args.length < 4) {
			say(sender, "<yellow>/patanim player <tên> <in|out> <hoạt ảnh|none></yellow>");
			return;
		}
		OfflinePlayer target = player(sender, args[1]);
		Selections.Kind kind = kind(args[2]);
		String anim = animation(sender, args[3]);
		if (target == null || kind == null || anim == null && !isNone(args[3])) {
			return;
		}
		plugin.selections().set(target.getUniqueId(), name(target, args[1]), kind, anim);
		plugin.saveSelections();
		say(sender, "<green>✔ " + name(target, args[1]) + " · " + (kind == Selections.Kind.INCOMING ? "khi được vỗ" : "khi vỗ người khác") + ": " + label(anim) + ".</green>");
		plugin.scheduleRebuild(sender);
	}

	private void setPair(CommandSender sender, String[] args) {
		if (args.length < 4) {
			say(sender, "<yellow>/patanim pair <người vỗ> <người được vỗ> <hoạt ảnh|none></yellow>");
			return;
		}
		OfflinePlayer from = player(sender, args[1]);
		OfflinePlayer to = player(sender, args[2]);
		String anim = animation(sender, args[3]);
		if (from == null || to == null || anim == null && !isNone(args[3])) {
			return;
		}
		plugin.selections().setPair(from.getUniqueId(), name(from, args[1]), to.getUniqueId(), name(to, args[2]), anim);
		plugin.saveSelections();
		say(sender, "<green>✔ Khi " + name(from, args[1]) + " vỗ " + name(to, args[2]) + ": " + label(anim) + ".</green>");
		plugin.scheduleRebuild(sender);
	}

	private void setDefault(CommandSender sender, String[] args) {
		if (args.length < 2) {
			say(sender, "<yellow>/patanim default <hoạt ảnh|none></yellow>");
			return;
		}
		String anim = animation(sender, args[1]);
		if (anim == null && !isNone(args[1])) {
			return;
		}
		plugin.selections().setDefault(anim);
		plugin.saveSelections();
		say(sender, "<green>✔ Mặc định cho mọi cú vỗ khác: " + label(anim) + ".</green>");
		plugin.scheduleRebuild(sender);
	}

	private void importPack(CommandSender sender, String[] args) {
		if (args.length < 3) {
			say(sender, "<yellow>/patanim import modrinth <slug|url>  ·  /patanim import file <tên.zip></yellow>");
			return;
		}
		String how = args[1].toLowerCase(Locale.ROOT);
		String what = args[2];
		say(sender, "<gray>Đang nhập " + what + "…</gray>");
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				byte[] zip;
				AnimationLibrary.Source source;
				if (how.equals("modrinth")) {
					ModrinthImporter.Download d = plugin.modrinth().fetch(what);
					zip = d.zip();
					source = d.source();
				} else if (how.equals("file")) {
					Path file = plugin.importFolder().resolve(what).normalize();
					if (!file.startsWith(plugin.importFolder()) || !Files.isRegularFile(file)) {
						throw new IOException("không có tệp " + what + " trong plugins/LunaPatPat/import/");
					}
					zip = Files.readAllBytes(file);
					String base = AnimationLibrary.slug(what.replaceAll("\\.zip$", ""));
					source = new AnimationLibrary.Source(base, null, null, null, null);
				} else {
					throw new IOException("chỉ hỗ trợ 'modrinth' hoặc 'file'");
				}
				List<String> ids;
				synchronized (plugin) {
					ids = plugin.library().importZip(zip, source);
				}
				plugin.tell(sender, "<green>✔ Đã nhập " + ids.size() + " hoạt ảnh: " + String.join(", ", ids) + "</green>");
				List<String> skipped = plugin.library().lastSkipped();
				if (!skipped.isEmpty()) {
					plugin.tell(sender, "<yellow>⚠ Bỏ qua " + skipped.size() + ": " + String.join("; ", skipped).replace("<", "‹") + "</yellow>");
				}
				plugin.scheduleRebuild(sender);
			} catch (IOException e) {
				plugin.tell(sender, "<red>❌ Nhập thất bại: " + e.getMessage() + "</red>");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private void remove(CommandSender sender, String[] args) {
		if (args.length < 2) {
			say(sender, "<yellow>/patanim remove <hoạt ảnh></yellow>");
			return;
		}
		String anim = animation(sender, args[1]);
		if (anim == null) {
			return;
		}
		try {
			synchronized (plugin) {
				plugin.library().remove(anim);
				plugin.selections().forget(anim);
			}
			plugin.saveSelections();
			say(sender, "<green>✔ Đã xoá " + anim + " và mọi lựa chọn dùng nó.</green>");
			plugin.scheduleRebuild(sender);
		} catch (IOException e) {
			say(sender, "<red>❌ " + e.getMessage() + "</red>");
		}
	}

	private void info(CommandSender sender, String[] args) {
		if (args.length < 2) {
			say(sender, "<yellow>/patanim info <hoạt ảnh></yellow>");
			return;
		}
		Animation a = plugin.library().get(args[1]);
		if (a == null) {
			say(sender, "<red>❌ Không có hoạt ảnh " + args[1] + ".</red>");
			return;
		}
		sender.sendMessage(Component.text(a.name() + " (" + a.id() + ")", NamedTextColor.GOLD));
		sender.sendMessage(Component.text("nguồn: " + a.source() + (a.sourceUrl() != null ? " " + a.sourceUrl() : ""), NamedTextColor.GRAY));
		if (a.author() != null || a.license() != null) {
			sender.sendMessage(Component.text((a.author() != null ? "tác giả: " + a.author() + "  " : "") + (a.license() != null ? "giấy phép: " + a.license() : ""), NamedTextColor.GRAY));
		}
		sender.sendMessage(Component.text(a.duration() + " ms · " + (a.frame().has("totalFrames") ? a.frame().get("totalFrames").getAsInt() : 1) + " khung · "
			+ (a.sounds() > 0 ? a.sounds() + " âm thanh" : a.soundRef() != null ? "âm thanh " + a.soundRef() : "không âm thanh"), NamedTextColor.GRAY));
	}

	private void rules(CommandSender sender) {
		Selections s = plugin.selections();
		say(sender, "<gold>─── Lựa chọn PatPat</gold>");
		say(sender, "<gray>Mặc định: </gray>" + label(s.defaultAnimation()));
		for (Selections.Pair p : s.pairs()) {
			say(sender, "<gray>" + p.fromName() + " → " + p.toName() + ": </gray>" + label(p.animation()));
		}
		for (Selections.PlayerChoice c : s.players()) {
			say(sender, "<gray>" + c.name + ": được vỗ </gray>" + label(c.incoming) + "<gray>, khi vỗ </gray>" + label(c.outgoing));
		}
	}

	private void help(CommandSender sender, boolean admin) {
		say(sender, "<gold>/patanim</gold> <gray>· mở giao diện chọn hoạt ảnh</gray>");
		say(sender, "<gold>/patanim list</gold> <gray>· xem và chọn hoạt ảnh trong khung chat</gray>");
		say(sender, "<gold>/patanim set <in|out> <hoạt ảnh|none></gold> <gray>· in: khi được vỗ, out: khi bạn vỗ</gray>");
		say(sender, "<gold>/patanim me</gold> <gray>· lựa chọn của bạn</gray>");
		if (admin) {
			say(sender, "<gold>/patanim player <tên> <in|out> <hoạt ảnh|none></gold>");
			say(sender, "<gold>/patanim pair <người vỗ> <người được vỗ> <hoạt ảnh|none></gold>");
			say(sender, "<gold>/patanim default <hoạt ảnh|none></gold>");
			say(sender, "<gold>/patanim import <modrinth|file> <slug/url|tên.zip></gold>");
			say(sender, "<gold>/patanim remove|info <hoạt ảnh> · rules · build · reload</gold>");
		}
	}

	// ---- tab completion ------------------------------------------------------------------------

	@Override
	public Collection<String> suggest(CommandSourceStack source, String[] args) {
		boolean admin = source.getSender().hasPermission(ADMIN);
		List<String> subs = new ArrayList<>(List.of("gui", "list", "me", "set", "info"));
		if (admin) subs.addAll(List.of("player", "pair", "default", "import", "remove", "rules", "build", "reload"));
		if (args.length <= 1) {
			return CommandCompletions.filterPrefix(subs, args.length == 0 ? "" : args[0]);
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		String last = args[args.length - 1];
		List<String> anims = new ArrayList<>(plugin.library().ids());
		List<String> animsOrNone = new ArrayList<>(anims);
		animsOrNone.add("none");
		List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		return switch (sub) {
			case "set" -> args.length == 2 ? CommandCompletions.filterPrefix(List.of("in", "out"), last)
				: args.length == 3 ? CommandCompletions.filterPrefix(animsOrNone, last) : List.of();
			case "player" -> args.length == 2 ? CommandCompletions.filterPrefix(names, last)
				: args.length == 3 ? CommandCompletions.filterPrefix(List.of("in", "out"), last)
				: args.length == 4 ? CommandCompletions.filterPrefix(animsOrNone, last) : List.of();
			case "pair" -> args.length <= 3 ? CommandCompletions.filterPrefix(names, last)
				: args.length == 4 ? CommandCompletions.filterPrefix(animsOrNone, last) : List.of();
			case "default" -> args.length == 2 ? CommandCompletions.filterPrefix(animsOrNone, last) : List.of();
			case "remove", "info" -> args.length == 2 ? CommandCompletions.filterPrefix(anims, last) : List.of();
			case "import" -> args.length == 2 ? CommandCompletions.filterPrefix(List.of("modrinth", "file"), last)
				: args.length == 3 && args[1].equalsIgnoreCase("file") ? CommandCompletions.filterPrefix(importFiles(), last) : List.of();
			default -> List.of();
		};
	}

	@Override
	public String permission() {
		return "lunapatpat.use";
	}

	// ---- helpers --------------------------------------------------------------------------------

	private List<String> importFiles() {
		try (var s = Files.list(plugin.importFolder())) {
			return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private boolean requireAdmin(CommandSender sender, boolean admin) {
		if (!admin) say(sender, "<red>❌ Lệnh này chỉ dành cho quản trị viên.</red>");
		return admin;
	}

	private Selections.Kind kind(String s) {
		return switch (s.toLowerCase(Locale.ROOT)) {
			case "in", "incoming", "duocvo" -> Selections.Kind.INCOMING;
			case "out", "outgoing", "vo" -> Selections.Kind.OUTGOING;
			default -> null;
		};
	}

	private static boolean isNone(String s) {
		return s.equalsIgnoreCase("none") || s.equalsIgnoreCase("default") || s.equals("-");
	}

	/** The animation id named, or null (and a message) when it is unknown; "none" gives null quietly. */
	private String animation(CommandSender sender, String s) {
		if (isNone(s)) return null;
		Animation a = plugin.library().get(s);
		if (a == null) {
			say(sender, "<red>❌ Không có hoạt ảnh " + s + ". Xem /patanim list.</red>");
			return null;
		}
		return a.id();
	}

	private OfflinePlayer player(CommandSender sender, String name) {
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) return online;
		OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
		if (cached == null) {
			say(sender, "<red>❌ Chưa từng thấy người chơi " + name + " trên máy chủ này.</red>");
		}
		return cached;
	}

	private static String name(OfflinePlayer p, String fallback) {
		return p.getName() != null ? p.getName() : fallback;
	}

	private String label(String anim) {
		if (anim == null) return "<gray>mặc định</gray>";
		Animation a = plugin.library().get(anim);
		return "<white>" + (a != null ? a.name().replace("<", "‹") : anim) + "</white>";
	}

	private Component choice(String anim) {
		Animation a = plugin.library().get(anim);
		return a == null ? Component.text("mặc định", NamedTextColor.DARK_GRAY) : Component.text(a.name(), NamedTextColor.WHITE);
	}

	private static Component button(String text, String command, String hover, NamedTextColor color) {
		return Component.text(text, color).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Component.text(hover)));
	}

	private static int parseInt(String s, int fallback) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static void say(CommandSender sender, String mini) {
		sender.sendMessage(LunaUi.mini(mini));
	}

	@SuppressWarnings("unused")
	private static UUID uuid(OfflinePlayer p) {
		return p.getUniqueId();
	}
}
