package dev.belikhun.luna.patpat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The /patanim menus.
 *
 * Main: what you use when patted and when patting (click to change). Admins also get: another
 * player's choices, pairs, the default, and a rebuild button. Every choice opens the animation
 * picker: the library, a page at a time, the current one glowing.
 */
final class PatPatGui {
	private static final String ADMIN = "lunapatpat.admin";
	private static final int PER_PAGE = 45;

	private final LunaPatPat plugin;

	PatPatGui(LunaPatPat plugin) {
		this.plugin = plugin;
	}

	// ---- main ---------------------------------------------------------------------------------

	void openMain(Player viewer) {
		openFor(viewer, viewer.getUniqueId(), viewer.getName());
	}

	/** The choices of one player, as seen (and changed) by {@code viewer}. */
	void openFor(Player viewer, UUID target, String targetName) {
		boolean self = viewer.getUniqueId().equals(target);
		boolean admin = viewer.hasPermission(ADMIN);
		Selections.PlayerChoice c = plugin.selections().get(target);
		Menu m = new Menu(admin && self ? 4 : 3, self ? "PatPat của bạn" : "PatPat · " + targetName);

		m.set(4, head(target, targetName, List.of(self ? "Lựa chọn của bạn" : "Lựa chọn của " + targetName)), null);
		m.set(11, Menu.item(Material.PINK_TULIP, "Khi được vỗ", List.of(
			"Hiện khi người khác vỗ đầu " + (self ? "bạn" : targetName),
			"Đang dùng: " + label(c == null ? null : c.incoming), "", "▸ Nhấn để đổi"), c != null && c.incoming != null),
			e -> pick(viewer, "Khi được vỗ", c == null ? null : c.incoming, anim -> {
				plugin.selections().set(target, targetName, Selections.Kind.INCOMING, anim);
				saved(viewer);
				openFor(viewer, target, targetName);
			}, () -> openFor(viewer, target, targetName)));
		m.set(15, Menu.item(Material.ALLIUM, "Khi vỗ người khác", List.of(
			"Hiện khi " + (self ? "bạn" : targetName) + " vỗ đầu người chơi khác",
			"Đang dùng: " + label(c == null ? null : c.outgoing), "", "▸ Nhấn để đổi"), c != null && c.outgoing != null),
			e -> pick(viewer, "Khi vỗ người khác", c == null ? null : c.outgoing, anim -> {
				plugin.selections().set(target, targetName, Selections.Kind.OUTGOING, anim);
				saved(viewer);
				openFor(viewer, target, targetName);
			}, () -> openFor(viewer, target, targetName)));
		m.set(13, Menu.item(Material.BOOK, "Thư viện", List.of(plugin.library().all().size() + " hoạt ảnh", "Cần mod PatPat để thấy")), null);

		if (admin && self) {
			m.set(28, Menu.item(Material.PLAYER_HEAD, "Người chơi khác", List.of("Đổi hoạt ảnh của một người chơi")),
				e -> choosePlayer(viewer, "Chọn người chơi", p -> openFor(viewer, p.getUniqueId(), name(p)), () -> openMain(viewer)));
			m.set(30, Menu.item(Material.ROSE_BUSH, "Cặp đôi", List.of(plugin.selections().pairs().size() + " cặp", "Hoạt ảnh riêng khi A vỗ B")),
				e -> openPairs(viewer, 0));
			m.set(32, Menu.item(Material.OAK_SAPLING, "Mặc định", List.of("Cho mọi cú vỗ khác", "Đang dùng: " + label(plugin.selections().defaultAnimation()), "", "▸ Nhấn để đổi"), false),
				e -> pick(viewer, "Mặc định", plugin.selections().defaultAnimation(), anim -> {
					plugin.selections().setDefault(anim);
					saved(viewer);
					openMain(viewer);
				}, () -> openMain(viewer)));
			m.set(34, Menu.item(Material.ANVIL, "Dựng lại gói", List.of("Dựng lại và gửi gói tới mọi người")),
				e -> {
					viewer.closeInventory();
					plugin.tell(viewer, "<gray>Đang dựng lại gói PatPat…</gray>");
					Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.rebuild(viewer));
				});
		}
		if (!self) {
			m.set(22, Menu.item(Material.ARROW, "Quay lại", List.of()), e -> openMain(viewer));
		}
		m.fillBorder(0);
		m.open(viewer);
	}

	// ---- pairs --------------------------------------------------------------------------------

	void openPairs(Player viewer, int page) {
		List<Selections.Pair> pairs = new ArrayList<>(plugin.selections().pairs());
		Menu m = new Menu(6, "Cặp đôi PatPat");
		int from = page * PER_PAGE;
		for (int i = 0; i < PER_PAGE && from + i < pairs.size(); i++) {
			Selections.Pair p = pairs.get(from + i);
			m.set(i, Menu.item(Material.POPPY, p.fromName() + " → " + p.toName(), List.of(
				"Hoạt ảnh: " + label(p.animation()), "", "▸ Nhấn: đổi hoạt ảnh", "▸ Shift + nhấn: xoá cặp"), false),
				e -> {
					if (e.isShiftClick()) {
						plugin.selections().setPair(p.from(), p.fromName(), p.to(), p.toName(), null);
						saved(viewer);
						openPairs(viewer, page);
						return;
					}
					pick(viewer, p.fromName() + " → " + p.toName(), p.animation(), anim -> {
						plugin.selections().setPair(p.from(), p.fromName(), p.to(), p.toName(), anim);
						saved(viewer);
						openPairs(viewer, page);
					}, () -> openPairs(viewer, page));
				});
		}
		if (page > 0) m.set(45, Menu.item(Material.ARROW, "Trang trước", List.of()), e -> openPairs(viewer, page - 1));
		if (from + PER_PAGE < pairs.size()) m.set(53, Menu.item(Material.ARROW, "Trang sau", List.of()), e -> openPairs(viewer, page + 1));
		m.set(49, Menu.item(Material.EMERALD, "Thêm cặp", List.of("Chọn người vỗ, người được vỗ, rồi hoạt ảnh")),
			e -> choosePlayer(viewer, "Ai vỗ?", a ->
				choosePlayer(viewer, name(a) + " vỗ ai?", b ->
					pick(viewer, name(a) + " → " + name(b), null, anim -> {
						plugin.selections().setPair(a.getUniqueId(), name(a), b.getUniqueId(), name(b), anim);
						saved(viewer);
						openPairs(viewer, 0);
					}, () -> openPairs(viewer, 0)),
					() -> openPairs(viewer, 0)),
				() -> openPairs(viewer, 0)));
		m.set(48, Menu.item(Material.BARRIER, "Quay lại", List.of()), e -> openMain(viewer));
		m.fillBorder(5);
		m.open(viewer);
	}

	// ---- pickers ------------------------------------------------------------------------------

	/** The animation picker; {@code done} gets the chosen id, or null for "default". */
	void pick(Player viewer, String title, String current, Consumer<String> done, Runnable back) {
		pickPage(viewer, title, current, done, back, 0);
	}

	private void pickPage(Player viewer, String title, String current, Consumer<String> done, Runnable back, int page) {
		List<Animation> all = new ArrayList<>(plugin.library().all());
		int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
		Menu m = new Menu(6, title + " (" + (page + 1) + "/" + pages + ")");
		int from = page * PER_PAGE;
		for (int i = 0; i < PER_PAGE && from + i < all.size(); i++) {
			Animation a = all.get(from + i);
			boolean on = a.id().equals(current);
			List<String> lore = new ArrayList<>();
			lore.add(a.id());
			lore.add((a.frame().has("totalFrames") ? a.frame().get("totalFrames").getAsInt() : 1) + " khung · " + a.duration() + " ms · " + (a.hasSound() ? "có âm thanh" : "không âm thanh"));
			if (a.author() != null) lore.add("tác giả: " + a.author());
			lore.add("");
			lore.add(on ? "✔ Đang dùng" : "▸ Nhấn để chọn");
			m.set(i, Menu.item(icon(a), a.name(), lore, on), e -> {
				viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
				done.accept(a.id());
			});
		}
		if (page > 0) m.set(45, Menu.item(Material.ARROW, "Trang trước", List.of()), e -> pickPage(viewer, title, current, done, back, page - 1));
		if (page + 1 < pages) m.set(53, Menu.item(Material.ARROW, "Trang sau", List.of()), e -> pickPage(viewer, title, current, done, back, page + 1));
		m.set(48, Menu.item(Material.BARRIER, "Quay lại", List.of()), e -> back.run());
		m.set(50, Menu.item(Material.GLASS_BOTTLE, "Mặc định", List.of("Bỏ lựa chọn này"), current == null), e -> done.accept(null));
		m.fillBorder(5);
		m.open(viewer);
	}

	/** Online players first, then everyone with a choice on record. */
	private void choosePlayer(Player viewer, String title, Consumer<OfflinePlayer> done, Runnable back) {
		List<OfflinePlayer> people = new ArrayList<>(Bukkit.getOnlinePlayers());
		for (Selections.PlayerChoice c : plugin.selections().players()) {
			if (people.stream().noneMatch(p -> p.getUniqueId().equals(c.uuid))) people.add(Bukkit.getOfflinePlayer(c.uuid));
		}
		for (Selections.Pair p : plugin.selections().pairs()) {
			for (UUID u : List.of(p.from(), p.to())) {
				if (people.stream().noneMatch(x -> x.getUniqueId().equals(u))) people.add(Bukkit.getOfflinePlayer(u));
			}
		}
		Menu m = new Menu(6, title);
		for (int i = 0; i < PER_PAGE && i < people.size(); i++) {
			OfflinePlayer p = people.get(i);
			m.set(i, head(p.getUniqueId(), name(p), List.of(p.isOnline() ? "đang trực tuyến" : "ngoại tuyến", "", "▸ Nhấn để chọn")), e -> done.accept(p));
		}
		m.set(49, Menu.item(Material.BARRIER, "Quay lại", List.of()), e -> back.run());
		m.fillBorder(5);
		m.open(viewer);
	}

	// ---- helpers ------------------------------------------------------------------------------

	private void saved(Player viewer) {
		plugin.saveSelections();
		viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
		plugin.tell(viewer, "<green>✔ Đã lưu.</green> <gray>Gói sẽ được gửi lại sau vài giây.</gray>");
		plugin.scheduleRebuild(viewer);
	}

	private String label(String anim) {
		Animation a = plugin.library().get(anim);
		return a == null ? "mặc định" : a.name();
	}

	private static String name(OfflinePlayer p) {
		return p.getName() != null ? p.getName() : p.getUniqueId().toString().substring(0, 8);
	}

	private static ItemStack head(UUID uuid, String name, List<String> lore) {
		ItemStack it = Menu.item(Material.PLAYER_HEAD, name, lore, false);
		SkullMeta meta = (SkullMeta) it.getItemMeta();
		meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
		it.setItemMeta(meta);
		return it;
	}

	/** An icon that hints at the animation, from words in its id. */
	private static Material icon(Animation a) {
		String id = a.id().toLowerCase(Locale.ROOT);
		String[][] map = {
			{"heart", "PINK_DYE"}, {"mwah", "RED_DYE"}, {"explosion", "TNT"}, {"nuke", "TNT_MINECART"}, {"bonk", "IRON_SHOVEL"},
			{"bba", "IRON_SHOVEL"}, {"mpa", "IRON_BARS"}, {"pipe", "IRON_BARS"}, {"-pa", "PISTON"}, {"piston", "PISTON"},
			{"hide", "GLASS"}, {"tha", "WOODEN_AXE"}, {"hammer", "WOODEN_AXE"}, {"tm-", "FEATHER"}, {"tickle", "FEATHER"},
			{"murmur", "WHITE_WOOL"}, {"meow", "STRING"}, {"blahaj", "LIGHT_BLUE_WOOL"}, {"dripstone", "POINTED_DRIPSTONE"},
			{"pyatochka", "LEATHER_BOOTS"}, {"mace", "MACE"}, {"stop", "BELL"}, {"pizza", "BREAD"}, {"loomian", "AMETHYST_SHARD"},
			{"slipper", "LEATHER_BOOTS"}, {"sausage", "COOKED_PORKCHOP"},
		};
		for (String[] m : map) {
			if (id.contains(m[0])) {
				Material mat = Material.matchMaterial(m[1]);
				if (mat != null) return mat;
			}
		}
		return Material.PAPER;
	}
}
