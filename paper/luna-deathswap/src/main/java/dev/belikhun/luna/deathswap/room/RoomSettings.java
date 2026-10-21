package dev.belikhun.luna.deathswap.room;

import org.bukkit.Difficulty;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Everything a room can be configured with. Each setting is declared once in
 * {@link #OPTIONS} with its type, bounds and description, and that one table
 * drives YAML load/save, /ds room set, its tab completion and /ds room info,
 * so adding a setting is one line.
 */
public final class RoomSettings {
	public enum Visibility {
		PUBLIC,
		PRIVATE
	}

	public String displayName = "";
	public Visibility visibility = Visibility.PUBLIC;
	public boolean persistent = false;
	public int maxPlayers = 16;
	public int minPlayers = 2;
	public boolean autoStart = true;
	public int startCountdown = 30;
	public int swapMin = 180;
	public int swapMax = 300;
	public int swapWarning = 10;
	/** Seconds before the first swap of a round; 0 = the normal swap-min..swap-max interval. */
	public int firstSwapMin = 0;
	/** Upper end of the random first-swap time; below first-swap-min it means exactly first-swap-min. */
	public int firstSwapMax = 0;
	/** Hide the countdown (???) until the warning for the first swap only, even when show-timer is on. */
	public boolean hideFirstSwap = false;
	public boolean showTimer = false;
	public boolean swapBossbar = true;
	public boolean locatorBar = false;
	/** While the timer is hidden, show "> 1 phút" / "< 1 phút" instead of ???. */
	public boolean vagueTimer = false;
	/** shuffle: new random ring each swap; random: anyone to anyone; ring: one fixed ring; pairs: fixed pairs. */
	public String swapPattern = "shuffle";
	/** none, food, basic (water + food), tools (stone tools + water + food). */
	public String kit = "none";
	public int maxHearts = 10;
	/** LifeSwap: a death costs hearts (stolen by whoever set the trap) instead of ending your round. */
	public boolean lifeswap = false;
	public int lifeswapSteal = 2;
	/** Max hearts every survivor loses at each swap (0 = off); out at 0 hearts. */
	public int swapDrain = 0;
	/** Minutes into a round before the play area starts closing in (0 = never). */
	public int shrinkAfter = 30;
	/** Minutes the border takes to close down to shrink-size. */
	public int shrinkTime = 10;
	/** Final border diameter in blocks. */
	public int shrinkSize = 100;
	/** After reaching shrink-size, minutes to close the rest of the way to nothing (0 = stop at shrink-size). */
	public int shrinkFinalTime = 5;
	/** Turn on PvP once the arena starts closing in. */
	public boolean shrinkPvp = true;
	/** When the arena phase starts, teleport every survivor near 0,0 into an arena of arena-size. */
	public boolean shrinkTeleport = true;
	/** Once the arena phase starts, a death eliminates (LifeSwap hearts no longer save you). */
	public boolean shrinkSuddenDeath = true;
	/** Arena diameter in blocks for shrink-teleport (500 = radius 250). */
	public int arenaSize = 500;
	public boolean keepMomentum = true;
	public int spreadRadius = 1500;
	public int worldBorder = 6000;
	public Difficulty difficulty = Difficulty.NORMAL;
	public boolean pvp = false;
	public int trapCredit = 30;
	public int endDelay = 10;
	/** 0 = a random world from the pool; anything else = generate that exact seed. */
	public long seed = 0L;

	/** One configurable value. */
	public record Option(
		String key,
		String type,
		String description,
		Function<RoomSettings, String> get,
		BiConsumer<RoomSettings, String> set,
		List<String> suggestions
	) {
	}

	public static final Map<String, Option> OPTIONS;

	static {
		Map<String, Option> map = new LinkedHashMap<>();
		add(map, text("name", "Tên hiển thị của phòng", s -> s.displayName, (s, v) -> s.displayName = v));
		add(map, choice("visibility", "public: ai cũng vào được; private: chỉ khi được thêm",
			s -> s.visibility.name().toLowerCase(Locale.ROOT),
			(s, v) -> s.visibility = Visibility.valueOf(v.toUpperCase(Locale.ROOT)), "public", "private"));
		add(map, bool("persistent", "Giữ phòng khi trống và qua khởi động lại", s -> s.persistent, (s, v) -> s.persistent = v));
		add(map, integer("max-players", "Số người tối đa trong phòng", 1, 100, s -> s.maxPlayers, (s, v) -> s.maxPlayers = v));
		add(map, integer("min-players", "Số người để tự bắt đầu", 1, 100, s -> s.minPlayers, (s, v) -> s.minPlayers = v));
		add(map, bool("auto-start", "Tự đếm ngược khi đủ người", s -> s.autoStart, (s, v) -> s.autoStart = v));
		add(map, integer("start-countdown", "Giây đếm ngược trước ván", 3, 600, s -> s.startCountdown, (s, v) -> s.startCountdown = v));
		add(map, integer("swap-min", "Khoảng hoán đổi ngắn nhất (giây)", 10, 3600, s -> s.swapMin, (s, v) -> s.swapMin = v));
		add(map, integer("swap-max", "Khoảng hoán đổi dài nhất (giây)", 10, 3600, s -> s.swapMax, (s, v) -> s.swapMax = v));
		add(map, integer("swap-warning", "Giây cảnh báo trước khi hoán đổi", 0, 60, s -> s.swapWarning, (s, v) -> s.swapWarning = v));
		add(map, integer("first-swap-min", "Lần hoán đổi đầu sớm nhất (giây), 0 = như bình thường", 0, 3600, s -> s.firstSwapMin, (s, v) -> s.firstSwapMin = v));
		add(map, integer("first-swap-max", "Lần hoán đổi đầu muộn nhất (giây)", 0, 3600, s -> s.firstSwapMax, (s, v) -> s.firstSwapMax = v));
		add(map, bool("hide-first-swap", "Ẩn giờ của lần hoán đổi đầu tiên (???)", s -> s.hideFirstSwap, (s, v) -> s.hideFirstSwap = v));
		add(map, bool("show-timer", "Hiện chính xác giờ hoán đổi", s -> s.showTimer, (s, v) -> s.showTimer = v));
		add(map, bool("swap-bossbar", "Thanh boss đếm tới lần hoán đổi", s -> s.swapBossbar, (s, v) -> s.swapBossbar = v));
		add(map, bool("locator-bar", "Hiện thanh định vị người chơi", s -> s.locatorBar, (s, v) -> s.locatorBar = v));
		add(map, bool("vague-timer", "Khi ẩn giờ: hiện '> 1 phút' / '< 1 phút' thay vì ???", s -> s.vagueTimer, (s, v) -> s.vagueTimer = v));
		add(map, choice("swap-pattern", "Cách hoán đổi: shuffle (vòng mới mỗi lần), random (ngẫu nhiên), ring (vòng cố định), pairs (theo cặp)",
			s -> s.swapPattern, (s, v) -> s.swapPattern = v.toLowerCase(Locale.ROOT), "shuffle", "random", "ring", "pairs"));
		add(map, choice("kit", "Đồ khởi đầu: none, food (đồ ăn), basic (xô nước + đồ ăn), tools (đồ đá + xô nước + đồ ăn)",
			s -> s.kit, (s, v) -> s.kit = v.toLowerCase(Locale.ROOT), "none", "food", "basic", "tools"));
		add(map, integer("max-hearts", "Số tim tối đa khi vào trận", 1, 50, s -> s.maxHearts, (s, v) -> s.maxHearts = v));
		add(map, bool("lifeswap", "LifeSwap: chết thì mất tim cho người gài bẫy, hết tim mới bị loại", s -> s.lifeswap, (s, v) -> s.lifeswap = v));
		add(map, integer("swap-drain", "Số tim tối đa mọi người mất sau mỗi lần hoán đổi, 0 = tắt", 0, 20, s -> s.swapDrain, (s, v) -> s.swapDrain = v));
		add(map, integer("lifeswap-steal", "Số tim bị cướp mỗi lần chết (LifeSwap)", 1, 10, s -> s.lifeswapSteal, (s, v) -> s.lifeswapSteal = v));
		add(map, integer("shrink-after", "Sau bao nhiêu phút thì vùng chơi thu hẹp, 0 = không", 0, 240, s -> s.shrinkAfter, (s, v) -> s.shrinkAfter = v));
		add(map, integer("shrink-time", "Số phút để viền thu hẹp xong", 1, 60, s -> s.shrinkTime, (s, v) -> s.shrinkTime = v));
		add(map, integer("shrink-size", "Đường kính vùng chơi cuối cùng (ô)", 16, 2000, s -> s.shrinkSize, (s, v) -> s.shrinkSize = v));
		add(map, integer("shrink-final-time", "Sau khi tới shrink-size, số phút để thu tiếp về 0 (chậm hơn), 0 = dừng", 0, 60, s -> s.shrinkFinalTime, (s, v) -> s.shrinkFinalTime = v));
		add(map, bool("shrink-sudden-death", "Ở đấu trường cuối, chết là bị loại luôn (không trừ tim)", s -> s.shrinkSuddenDeath, (s, v) -> s.shrinkSuddenDeath = v));
		add(map, bool("shrink-teleport", "Khi thu hẹp: đưa mọi người về gần 0,0 trong đấu trường", s -> s.shrinkTeleport, (s, v) -> s.shrinkTeleport = v));
		add(map, integer("arena-size", "Đường kính đấu trường khi đưa về 0,0 (ô)", 64, 2000, s -> s.arenaSize, (s, v) -> s.arenaSize = v));
		add(map, bool("shrink-pvp", "Bật PvP khi vùng chơi bắt đầu thu hẹp", s -> s.shrinkPvp, (s, v) -> s.shrinkPvp = v));
		add(map, bool("keep-momentum", "Giữ đà rơi và vận tốc khi đổi", s -> s.keepMomentum, (s, v) -> s.keepMomentum = v));
		add(map, integer("spread-radius", "Bán kính rải người lúc đầu", 32, 20000, s -> s.spreadRadius, (s, v) -> s.spreadRadius = v));
		add(map, integer("world-border", "Đường kính viền thế giới", 256, 60000, s -> s.worldBorder, (s, v) -> s.worldBorder = v));
		add(map, choice("difficulty", "Độ khó của thế giới",
			s -> s.difficulty.name().toLowerCase(Locale.ROOT),
			(s, v) -> s.difficulty = Difficulty.valueOf(v.toUpperCase(Locale.ROOT)), "peaceful", "easy", "normal", "hard"));
		add(map, bool("pvp", "Cho phép đánh nhau trực tiếp", s -> s.pvp, (s, v) -> s.pvp = v));
		add(map, integer("trap-credit", "Giây sau hoán đổi tính là bẫy", 0, 600, s -> s.trapCredit, (s, v) -> s.trapCredit = v));
		add(map, integer("end-delay", "Giây xem kết quả trước khi về sảnh", 3, 120, s -> s.endDelay, (s, v) -> s.endDelay = v));
		add(map, new Option("seed", "long", "Seed cố định (0 = ngẫu nhiên, dùng thế giới chờ sẵn)",
			s -> String.valueOf(s.seed),
			(s, v) -> s.seed = v.equalsIgnoreCase("random") ? 0L : Long.parseLong(v),
			List.of("random", "0")));
		OPTIONS = Collections.unmodifiableMap(map);
	}

	/** Apply one value; returns null on success or the reason it was refused. */
	public String set(String key, String value) {
		Option option = OPTIONS.get(key.toLowerCase(Locale.ROOT));
		if (option == null) {
			return "Không có thiết lập '" + key + "'.";
		}
		RoomSettings trial = copy();
		try {
			option.set().accept(trial, value);
		} catch (IllegalArgumentException exception) {
			return exception.getMessage() == null ? "Giá trị không hợp lệ." : exception.getMessage();
		}
		if (trial.swapMax < trial.swapMin) {
			return "swap-max phải lớn hơn hoặc bằng swap-min (" + trial.swapMin + ").";
		}
		if (trial.swapWarning > trial.swapMin) {
			return "swap-warning không được lớn hơn swap-min (" + trial.swapMin + ").";
		}
		if (trial.firstSwapMin > 0 && trial.firstSwapMin < trial.swapWarning) {
			return "first-swap-min phải là 0 hoặc không nhỏ hơn swap-warning (" + trial.swapWarning + ").";
		}
		if (trial.firstSwapMin > 0 && trial.firstSwapMax > 0 && trial.firstSwapMax < trial.firstSwapMin) {
			return "first-swap-max phải lớn hơn hoặc bằng first-swap-min (" + trial.firstSwapMin + ").";
		}
		if (trial.minPlayers > trial.maxPlayers) {
			return "min-players không được lớn hơn max-players (" + trial.maxPlayers + ").";
		}
		option.set().accept(this, value);
		return null;
	}

	public String get(String key) {
		Option option = OPTIONS.get(key.toLowerCase(Locale.ROOT));
		return option == null ? null : option.get().apply(this);
	}

	public RoomSettings copy() {
		RoomSettings copy = new RoomSettings();
		for (Option option : OPTIONS.values()) {
			option.set().accept(copy, option.get().apply(this));
		}
		return copy;
	}

	/** Read every known key present in the section; missing keys keep this object's values. */
	public RoomSettings load(ConfigurationSection section) {
		if (section == null) {
			return this;
		}
		for (Option option : OPTIONS.values()) {
			if (section.isSet(option.key())) {
				try {
					option.set().accept(this, String.valueOf(section.get(option.key())));
				} catch (IllegalArgumentException ignored) {
					// a bad value in a file keeps the default
				}
			}
		}
		if (swapMax < swapMin) {
			swapMax = swapMin;
		}
		swapWarning = Math.min(swapWarning, swapMin);
		// rooms saved before the range existed have a single fixed first-swap
		if (!section.isSet("first-swap-min") && section.isInt("first-swap")) {
			firstSwapMin = Math.max(0, Math.min(3600, section.getInt("first-swap")));
			firstSwapMax = firstSwapMin;
		}
		if (firstSwapMin > 0 && firstSwapMin < swapWarning) {
			firstSwapMin = swapWarning;
		}
		if (firstSwapMax > 0 && firstSwapMax < firstSwapMin) {
			firstSwapMax = firstSwapMin;
		}
		minPlayers = Math.min(minPlayers, maxPlayers);
		return this;
	}

	public void save(ConfigurationSection section) {
		section.set("first-swap", null); // replaced by first-swap-min/max
		for (Option option : OPTIONS.values()) {
			String value = option.get().apply(this);
			switch (option.type()) {
				case "int" -> section.set(option.key(), Integer.parseInt(value));
				case "long" -> section.set(option.key(), Long.parseLong(value));
				case "bool" -> section.set(option.key(), Boolean.parseBoolean(value));
				default -> section.set(option.key(), value);
			}
		}
	}

	public static List<String> keys() {
		return new ArrayList<>(OPTIONS.keySet());
	}

	// ------------------------------------------------------------------

	private static void add(Map<String, Option> map, Option option) {
		map.put(option.key(), option);
	}

	private static Option text(String key, String description, Function<RoomSettings, String> get, BiConsumer<RoomSettings, String> set) {
		return new Option(key, "text", description, get, (s, v) -> {
			if (v.length() > 64) {
				throw new IllegalArgumentException("Tối đa 64 ký tự.");
			}
			set.accept(s, v);
		}, List.of());
	}

	private static Option bool(String key, String description, Function<RoomSettings, Boolean> get, BiConsumer<RoomSettings, Boolean> set) {
		return new Option(key, "bool", description, s -> String.valueOf(get.apply(s)), (s, v) -> {
			String value = v.toLowerCase(Locale.ROOT);
			switch (value) {
				case "true", "on", "yes", "bật", "1" -> set.accept(s, true);
				case "false", "off", "no", "tắt", "0" -> set.accept(s, false);
				default -> throw new IllegalArgumentException("Cần true hoặc false.");
			}
		}, List.of("true", "false"));
	}

	private static Option integer(String key, String description, int min, int max, Function<RoomSettings, Integer> get, BiConsumer<RoomSettings, Integer> set) {
		return new Option(key, "int", description + " (" + min + "–" + max + ")", s -> String.valueOf(get.apply(s)), (s, v) -> {
			int value;
			try {
				value = Integer.parseInt(v.trim());
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Cần một số nguyên.");
			}
			if (value < min || value > max) {
				throw new IllegalArgumentException("Cần trong khoảng " + min + "–" + max + ".");
			}
			set.accept(s, value);
		}, List.of(String.valueOf(min), String.valueOf(max)));
	}

	private static Option choice(String key, String description, Function<RoomSettings, String> get, BiConsumer<RoomSettings, String> set, String... values) {
		List<String> allowed = List.of(values);
		return new Option(key, "choice", description, get, (s, v) -> {
			if (!allowed.contains(v.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("Cần một trong: " + String.join(", ", allowed) + ".");
			}
			set.accept(s, v);
		}, allowed);
	}
}
