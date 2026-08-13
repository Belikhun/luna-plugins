package dev.belikhun.luna.legacy.permission;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a 1.12.2 backend answers a permission check with no LuckPerms of its own.
 *
 * The cases below are the ones an operator can produce from the console in a minute,
 * and each of them has a wrong answer that looks reasonable.
 */
class PermissionSnapshotTest {
	private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@Test
	void anExactNodeWins() {
		PermissionSnapshot snapshot = snapshot(permissions("luna.admin.kick", true));

		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.kick"));
	}

	@Test
	void aWildcardCoversItsChildren() {
		PermissionSnapshot snapshot = snapshot(permissions("luna.admin.*", true));

		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.kick"));
		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.ban.permanent"));
		assertEquals(Tristate.UNDEFINED, snapshot.check("luna.shop.buy"));
	}

	/**
	 * The case the whole ordering exists for: a group is granted a whole branch and
	 * one verb is taken back off one person. A snapshot that let the wildcard win here
	 * would hand that person the verb their operator explicitly removed.
	 */
	@Test
	void theMoreSpecificNodeBeatsTheWildcard() {
		Map<String, Boolean> permissions = new LinkedHashMap<String, Boolean>();
		permissions.put("luna.admin.*", Boolean.TRUE);
		permissions.put("luna.admin.kick", Boolean.FALSE);

		PermissionSnapshot snapshot = snapshot(permissions);

		assertEquals(Tristate.FALSE, snapshot.check("luna.admin.kick"));
		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.ban"));
	}

	@Test
	void aNarrowerWildcardBeatsAWiderOne() {
		Map<String, Boolean> permissions = new LinkedHashMap<String, Boolean>();
		permissions.put("*", Boolean.TRUE);
		permissions.put("luna.admin.*", Boolean.FALSE);

		PermissionSnapshot snapshot = snapshot(permissions);

		assertEquals(Tristate.FALSE, snapshot.check("luna.admin.kick"));
		assertEquals(Tristate.TRUE, snapshot.check("luna.shop.buy"));
	}

	/**
	 * The trap this whole class is shaped around. An unset node is not a denial, and a
	 * player nobody has fetched yet must read as unset rather than as denied - or every
	 * permission whose default is *allowed* switches off while the snapshot is in flight.
	 */
	@Test
	void anUnsetNodeIsUndefinedRatherThanDenied() {
		PermissionSnapshot snapshot = snapshot(permissions("luna.admin.kick", true));

		assertEquals(Tristate.UNDEFINED, snapshot.check("luna.hat.use"));
		assertTrue(snapshot.check("luna.hat.use").orElse(true), "a default-allow node was denied by an unset lookup");
		assertFalse(snapshot.check("luna.hat.use").orElse(false), "a default-deny node was granted by an unset lookup");
	}

	@Test
	void anExplicitDenialIsNotTheSameAsUnset() {
		PermissionSnapshot snapshot = snapshot(permissions("luna.hat.use", false));

		assertEquals(Tristate.FALSE, snapshot.check("luna.hat.use"));
		assertFalse(snapshot.check("luna.hat.use").orElse(true), "an explicit denial was overridden by the fallback");
	}

	@Test
	void lookupsAreCaseInsensitive() {
		PermissionSnapshot snapshot = snapshot(permissions("Luna.Admin.Kick", true));

		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.KICK"));
	}

	@Test
	void groupsAreReported() {
		PermissionSnapshot snapshot = new PermissionSnapshot(
			PLAYER, "belikhun", "admin", "&c[Admin] ", "", Arrays.asList("admin", "default"),
			permissions("luna.admin.*", true), System.currentTimeMillis()
		);

		assertTrue(snapshot.inGroup("Admin"));
		assertFalse(snapshot.inGroup("moderator"));
		assertEquals("admin", snapshot.primaryGroup());
	}

	// ------------------------------------------------------------------ the wire

	@Test
	void aSnapshotSurvivesTheWire() {
		Map<String, Boolean> permissions = new LinkedHashMap<String, Boolean>();
		permissions.put("luna.admin.*", Boolean.TRUE);
		permissions.put("luna.admin.kick", Boolean.FALSE);
		permissions.put("luna.shop.buy", Boolean.TRUE);

		PermissionSnapshot original = new PermissionSnapshot(
			PLAYER, "belikhun", "admin", "<gradient:#5FE2C5:#9BC1F9>Quản trị</gradient> ", " &7[VIP]",
			Arrays.asList("admin", "default"), permissions, System.currentTimeMillis()
		);

		PermissionSnapshot decoded = PermissionSnapshotCodec.decode(PermissionSnapshotCodec.encode(original));

		assertEquals(original.uniqueId(), decoded.uniqueId());
		assertEquals(original.username(), decoded.username());
		assertEquals(original.primaryGroup(), decoded.primaryGroup());
		// the prefix carries both a MiniMessage tag and a non-ASCII word, which is
		// exactly what a platform-default charset would corrupt
		assertEquals(original.prefix(), decoded.prefix());
		assertEquals(original.suffix(), decoded.suffix());
		assertEquals(original.groups(), decoded.groups());
		assertEquals(original.permissions(), decoded.permissions());
		assertEquals(Tristate.FALSE, decoded.check("luna.admin.kick"));
	}

	/**
	 * An error page, a truncated body or a 404 that still returned 200 all look the
	 * same from here, and none of them may be cached as "this player has no
	 * permissions" - that would deny everything until the entry aged out.
	 */
	@Test
	void aBodyWithNoUuidDecodesToNothing() {
		assertNull(PermissionSnapshotCodec.decode("<html>404</html>".getBytes()));
		assertNull(PermissionSnapshotCodec.decode(new byte[0]));
		assertNull(PermissionSnapshotCodec.decode("protocol=1&uuid=not-a-uuid".getBytes()));
	}

	private static PermissionSnapshot snapshot(Map<String, Boolean> permissions) {
		return new PermissionSnapshot(
			PLAYER, "belikhun", "default", "", "", Arrays.asList("default"), permissions, System.currentTimeMillis()
		);
	}

	private static Map<String, Boolean> permissions(String key, boolean value) {
		Map<String, Boolean> out = new LinkedHashMap<String, Boolean>();
		out.put(key, Boolean.valueOf(value));
		return out;
	}
}
