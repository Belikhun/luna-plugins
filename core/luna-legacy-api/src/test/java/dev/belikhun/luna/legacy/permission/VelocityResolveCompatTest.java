package dev.belikhun.luna.legacy.permission;

import dev.belikhun.luna.legacy.http.LegacyHttp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java 8 decoder against a body the real proxy really sent.
 *
 * `PermissionSnapshotCodec` and the proxy's `/permissions/resolve/{player}` are two
 * implementations of one contract, in two jars, on two Java versions, and nothing in
 * either build would notice the other changing. So the fixture beside this test is a
 * verbatim capture from a running Velocity proxy with LuckPerms - not a hand-written
 * approximation - and this asserts the decoder still reads it.
 *
 * The captured player is deliberately awkward: they inherit `luna.admin.*` from a
 * group, carry a **more specific denial** of `luna.admin.kick` on their own account,
 * and hold one node scoped to `server=forge12test` that only appears because the
 * request named that server.
 */
class VelocityResolveCompatTest {
	private static final String FIXTURE = "permission-resolve-velocity.txt";

	@Test
	void theDecoderReadsWhatTheProxySends() {
		PermissionSnapshot snapshot = PermissionSnapshotCodec.decode(fixture());

		assertNotNull(snapshot, "the proxy's body decoded to nothing");
		assertEquals("91acb76d-4c4b-4899-9e19-d9d2fd4b0711", snapshot.uniqueId().toString());
		assertEquals("belikhun", snapshot.username());
		assertEquals("mc12admin", snapshot.primaryGroup());

		// `[MC12]` arrives percent-encoded; a decoder that skipped that would hand the
		// brackets through as %5B / %5D
		assertEquals("[MC12]", snapshot.prefix());
	}

	@Test
	void inheritedGroupsSurvive() {
		PermissionSnapshot snapshot = PermissionSnapshotCodec.decode(fixture());

		assertTrue(snapshot.inGroup("mc12admin"), "the group granting the wildcard is missing");
		assertTrue(snapshot.inGroup("default"), "the inherited default group is missing");
	}

	/**
	 * The whole point of resolving on the proxy: the backend never sees the group, only
	 * the permission the group granted.
	 */
	@Test
	void aGroupsWildcardArrivesAsAPermission() {
		PermissionSnapshot snapshot = PermissionSnapshotCodec.decode(fixture());

		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.ban"));
		assertEquals(Tristate.TRUE, snapshot.check("luna.admin.anything.at.all"));
	}

	@Test
	void aPersonalDenialStillBeatsTheInheritedWildcard() {
		PermissionSnapshot snapshot = PermissionSnapshotCodec.decode(fixture());

		assertEquals(Tristate.FALSE, snapshot.check("luna.admin.kick"));
		assertFalse(snapshot.check("luna.admin.kick").orElse(true), "the denial was overridden by a default-allow read");
	}

	/**
	 * This node exists only because the request named `server=forge12test`. Its
	 * presence is what proves the backend's own context reached LuckPerms - the same
	 * request without a server comes back one node shorter.
	 */
	@Test
	void aServerScopedNodeIsPresentBecauseTheServerWasNamed() {
		PermissionSnapshot snapshot = PermissionSnapshotCodec.decode(fixture());

		assertEquals(Tristate.TRUE, snapshot.check("luna.mc12.scoped"));
	}

	private static byte[] fixture() {
		InputStream stream = VelocityResolveCompatTest.class.getClassLoader().getResourceAsStream(FIXTURE);

		assertNotNull(stream, FIXTURE + " is missing from the test resources");

		try {
			try {
				return LegacyHttp.drain(stream);
			} finally {
				stream.close();
			}
		} catch (IOException failure) {
			throw new AssertionError("could not read " + FIXTURE, failure);
		}
	}
}
