package dev.belikhun.luna.pack.service;

import com.velocitypowered.api.network.ProtocolVersion;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackFormat;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maps a client's protocol version to the resource-pack format that client
 * expects, which is what a pack's declared range is checked against.
 *
 * The table holds change points only and is looked up by floor: protocols
 * between two entries resolve to the older one (formats only move on releases
 * that also move the protocol), and a protocol newer than the last entry
 * resolves to the newest known format. That newest-known fallback
 * under-estimates future clients, so it is logged once per unknown protocol;
 * the config's client-formats section is the correction (new entries override
 * or extend the built-ins without a plugin build).
 */
public final class ClientFormatService {
	/**
	 * Resource-pack format change points, protocol → format. Entries through
	 * 772 are the published history; 767, 772, 773 and 774 are verified against
	 * the version.json inside this cluster's own server jars.
	 */
	private static final NavigableMap<Integer, PackFormat> BUILT_IN;

	static {
		NavigableMap<Integer, PackFormat> table = new TreeMap<>();
		table.put(0, new PackFormat(1, 0));      // 1.6.1-1.8.9
		table.put(107, new PackFormat(2, 0));    // 1.9
		table.put(315, new PackFormat(3, 0));    // 1.11
		table.put(393, new PackFormat(4, 0));    // 1.13
		table.put(573, new PackFormat(5, 0));    // 1.15
		table.put(751, new PackFormat(6, 0));    // 1.16.2
		table.put(755, new PackFormat(7, 0));    // 1.17
		table.put(757, new PackFormat(8, 0));    // 1.18
		table.put(759, new PackFormat(9, 0));    // 1.19
		table.put(761, new PackFormat(12, 0));   // 1.19.3
		table.put(762, new PackFormat(13, 0));   // 1.19.4
		table.put(763, new PackFormat(15, 0));   // 1.20
		table.put(764, new PackFormat(18, 0));   // 1.20.2
		table.put(765, new PackFormat(22, 0));   // 1.20.3
		table.put(766, new PackFormat(32, 0));   // 1.20.5
		table.put(767, new PackFormat(34, 0));   // 1.21
		table.put(768, new PackFormat(42, 0));   // 1.21.2
		table.put(769, new PackFormat(46, 0));   // 1.21.4
		table.put(770, new PackFormat(55, 0));   // 1.21.5
		table.put(771, new PackFormat(63, 0));   // 1.21.6
		table.put(772, new PackFormat(64, 0));   // 1.21.7
		table.put(773, new PackFormat(69, 0));   // 1.21.10
		table.put(774, new PackFormat(75, 0));   // 1.21.11
		BUILT_IN = table;
	}

	private final LunaLogger logger;
	private final AtomicReference<NavigableMap<Integer, PackFormat>> table;
	private final Set<Integer> reportedUnknown;
	private volatile boolean enabled;

	public ClientFormatService(LunaLogger logger) {
		this.logger = logger.scope("ClientFormat");
		this.table = new AtomicReference<>(BUILT_IN);
		this.reportedUnknown = ConcurrentHashMap.newKeySet();
		this.enabled = true;
	}

	/** Apply the loader config: the filter toggle and the operator's table entries. */
	public void configure(boolean versionFilter, Map<Integer, PackFormat> overrides) {
		enabled = versionFilter;

		if (overrides == null || overrides.isEmpty()) {
			table.set(BUILT_IN);
			return;
		}

		NavigableMap<Integer, PackFormat> merged = new TreeMap<>(BUILT_IN);
		merged.putAll(overrides);
		table.set(merged);
		logger.audit("Đã nạp " + overrides.size() + " mục client-formats từ config.");
	}

	/**
	 * The pack format of a client on `version`, or null when filtering is off.
	 * Never null while filtering is on: the table's floor entry covers every
	 * protocol Velocity accepts.
	 */
	public PackFormat formatFor(ProtocolVersion version) {
		if (!enabled) {
			return null;
		}

		int protocol = version.getProtocol();
		NavigableMap<Integer, PackFormat> current = table.get();
		Map.Entry<Integer, PackFormat> entry = current.floorEntry(protocol);

		if (entry == null) {
			return current.firstEntry().getValue();
		}

		if (protocol > current.lastKey() && reportedUnknown.add(protocol)) {
			logger.warn("Chưa biết pack format cho protocol " + protocol
				+ ", tạm coi là " + entry.getValue().render()
				+ ". Thêm mục client-formats vào config.yml nếu cần chính xác hơn.");
		}

		return entry.getValue();
	}

	public boolean enabled() {
		return enabled;
	}
}
