package dev.belikhun.luna.core.velocity.tablist;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabListEntry;
import dev.belikhun.luna.core.api.http.LunaJson;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures the tab list a player sees and renders it as text, so the list can
 * be inspected from the console or over HTTP without a client connected.
 *
 * The header and footer live on Velocity's connected-player object, which is
 * not API. They are read by reflection and reported as unreadable, never as
 * empty, when the accessor is missing on the running proxy build.
 */
public final class VelocityTabListRenderer {
	/** How the Minecraft client fills the tab list: columns of this many rows. */
	private static final int ROWS_PER_COLUMN = 20;
	private static final String HEADER_ACCESSOR = "getPlayerListHeader";
	private static final String FOOTER_ACCESSOR = "getPlayerListFooter";
	private static final MiniMessage MINI = MiniMessage.miniMessage();
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

	/** The text form a render is asked for. */
	public enum Format {
		PLAIN,
		MINI,
		LEGACY;

		/**
		 * Parses a format name leniently.
		 *
		 * @param value the name as typed, may be null
		 * @return the format, or PLAIN when the name is unknown
		 */
		public static Format parse(String value) {
			if (value == null) {
				return PLAIN;
			}

			for (Format format : values()) {
				if (format.name().equalsIgnoreCase(value.trim())) {
					return format;
				}
			}

			return PLAIN;
		}
	}

	private final LunaLogger logger;
	private final AtomicBoolean accessorWarned = new AtomicBoolean(false);

	public VelocityTabListRenderer(LunaLogger logger) {
		this.logger = logger.scope("TabListRender");
	}

	/**
	 * Takes a snapshot of what the player sees right now.
	 *
	 * @param viewer the player whose tab list is captured
	 * @return the capture, with entries already in client sort order
	 */
	public TabListCapture capture(Player viewer) {
		List<TabListCapture.Entry> entries = new ArrayList<>();

		for (TabListEntry entry : viewer.getTabList().getEntries()) {
			entries.add(new TabListCapture.Entry(
				entry.getProfile().getId(),
				entry.getProfile().getName(),
				entry.getDisplayNameComponent().orElse(null),
				entry.getLatency(),
				entry.getGameMode(),
				entry.isListed(),
				entry.getListOrder()
			));
		}

		// The client orders by explicit list order first (highest on top), then by
		// profile name. Team-based sorting on older clients is not visible from
		// here; the render says so in its footer line.
		entries.sort(Comparator
			.comparingInt(TabListCapture.Entry::listOrder).reversed()
			.thenComparing(TabListCapture.Entry::profileName, String.CASE_INSENSITIVE_ORDER));

		Component header = readComponent(viewer, HEADER_ACCESSOR);
		Component footer = readComponent(viewer, FOOTER_ACCESSOR);
		boolean readable = accessorsPresent(viewer);
		String server = viewer.getCurrentServer()
			.map(connection -> connection.getServerInfo().getName())
			.orElse(null);

		return new TabListCapture(
			viewer.getUniqueId(),
			viewer.getUsername(),
			server,
			header,
			footer,
			readable,
			List.copyOf(entries),
			System.currentTimeMillis()
		);
	}

	/**
	 * Renders a capture as lines of text.
	 *
	 * @param capture the capture to render
	 * @param format  how components are written out
	 * @return one string per output line
	 */
	public List<String> renderLines(TabListCapture capture, Format format) {
		List<String> lines = new ArrayList<>();
		String server = capture.viewerServer() == null ? "?" : capture.viewerServer();

		lines.add("── tab list · " + capture.viewerName() + " @ " + server
			+ " · " + capture.entries().size() + " entries · " + format.name().toLowerCase(Locale.ROOT) + " ──");

		lines.add("[header]");
		if (!capture.headerFooterReadable()) {
			lines.add("  (unreadable on this proxy build)");
		} else if (capture.header() == null) {
			lines.add("  (none)");
		} else {
			for (String line : serialize(capture.header(), format).split("\n", -1)) {
				lines.add("  " + line);
			}
		}

		lines.add("[entries]");
		int index = 0;
		for (TabListCapture.Entry entry : capture.entries()) {
			index++;
			int column = (index - 1) / ROWS_PER_COLUMN + 1;
			int row = (index - 1) % ROWS_PER_COLUMN + 1;
			String display = entry.displayName() == null
				? entry.profileName()
				: serialize(entry.displayName(), format);
			String hidden = entry.listed() ? "" : " hidden";

			lines.add(String.format(
				Locale.ROOT,
				"  c%d r%02d  %s   (%s · %dms · gm%d · order %d%s)",
				column,
				row,
				display,
				entry.profileName(),
				entry.latencyMillis(),
				entry.gameMode(),
				entry.listOrder(),
				hidden
			));
		}

		lines.add("[footer]");
		if (!capture.headerFooterReadable()) {
			lines.add("  (unreadable on this proxy build)");
		} else if (capture.footer() == null) {
			lines.add("  (none)");
		} else {
			for (String line : serialize(capture.footer(), format).split("\n", -1)) {
				lines.add("  " + line);
			}
		}

		lines.add("── sorted by list order then name; team-based sorting is not visible from the proxy ──");
		return lines;
	}

	/**
	 * Renders a capture as a JSON object for the HTTP surface.
	 *
	 * @param capture the capture to render
	 * @return the payload map
	 */
	public Map<String, Object> toJson(TabListCapture capture) {
		LunaJson.Arr entries = LunaJson.arr();

		for (TabListCapture.Entry entry : capture.entries()) {
			entries.add(LunaJson.obj()
				.put("uuid", entry.profileId().toString())
				.put("name", entry.profileName())
				.put("displayMini", entry.displayName() == null ? null : MINI.serialize(entry.displayName()))
				.put("displayPlain", entry.displayName() == null ? entry.profileName() : PLAIN.serialize(entry.displayName()))
				.put("latencyMillis", entry.latencyMillis())
				.put("gameMode", entry.gameMode())
				.put("listed", entry.listed())
				.put("listOrder", entry.listOrder())
				.map());
		}

		return LunaJson.obj()
			.put("viewer", LunaJson.obj()
				.put("uuid", capture.viewerId().toString())
				.put("name", capture.viewerName())
				.put("server", capture.viewerServer())
				.map())
			.put("headerFooterReadable", capture.headerFooterReadable())
			.put("headerMini", capture.header() == null ? null : MINI.serialize(capture.header()))
			.put("headerPlain", capture.header() == null ? null : PLAIN.serialize(capture.header()))
			.put("footerMini", capture.footer() == null ? null : MINI.serialize(capture.footer()))
			.put("footerPlain", capture.footer() == null ? null : PLAIN.serialize(capture.footer()))
			.put("entries", entries.list())
			.put("capturedAtEpochMillis", capture.capturedAtEpochMillis())
			.map();
	}

	private String serialize(Component component, Format format) {
		return switch (format) {
			case PLAIN -> PLAIN.serialize(component);
			case MINI -> MINI.serialize(component);
			case LEGACY -> LEGACY.serialize(component);
		};
	}

	private boolean accessorsPresent(Player viewer) {
		return accessor(viewer, HEADER_ACCESSOR) != null && accessor(viewer, FOOTER_ACCESSOR) != null;
	}

	private Component readComponent(Player viewer, String accessorName) {
		Method accessor = accessor(viewer, accessorName);

		if (accessor == null) {
			return null;
		}

		try {
			Object value = accessor.invoke(viewer);
			return value instanceof Component component ? component : null;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			warnOnce("Không đọc được " + accessorName + " trên " + viewer.getClass().getName() + ": " + exception);
			return null;
		}
	}

	private Method accessor(Player viewer, String accessorName) {
		try {
			return viewer.getClass().getMethod(accessorName);
		} catch (NoSuchMethodException exception) {
			warnOnce("Bản Velocity này không có " + accessorName + " trên " + viewer.getClass().getName()
				+ "; header/footer sẽ được báo là không đọc được.");
			return null;
		}
	}

	private void warnOnce(String message) {
		if (accessorWarned.compareAndSet(false, true)) {
			logger.warn(message);
		}
	}
}
