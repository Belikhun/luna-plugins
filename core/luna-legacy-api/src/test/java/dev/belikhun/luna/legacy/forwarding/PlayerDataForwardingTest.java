package dev.belikhun.luna.legacy.forwarding;

import dev.belikhun.luna.legacy.exception.LunaLegacyException;
import dev.belikhun.luna.legacy.http.LegacyHttp;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Our forwarding reader against bytes Velocity's own encoder produced.
 *
 * The fixture beside this test is not hand-written: it is the output of
 * `PlayerDataForwarding.createForwardingData` called on the actual proxy jar we
 * run, through the generator kept next to it. That matters more here than
 * anywhere else in the port - this codec is what decides *who a player is*, and
 * a reader that merely looks right would hand out the wrong identity or, worse,
 * accept a forged one.
 */
class PlayerDataForwardingTest {
	private static final String FIXTURE = "forwarding-vector-velocity.txt";

	/** The secret the fixture was signed with; the dev cluster's own. */
	private static final String SECRET = "01jO4qNY6QGbRUhR";

	@Test
	void readsWhatVelocityWrote() {
		ForwardedPlayer player = PlayerDataForwarding.verifyAndParse(vector(), SECRET);

		assertEquals(PlayerDataForwarding.MODERN_DEFAULT, player.version());
		assertEquals(UUID.fromString("91acb76d-4c4b-4899-9e19-d9d2fd4b0711"), player.uniqueId());
		assertEquals("Belikhun", player.username());

		// the reason forwarding exists: without it this is the proxy's address
		assertEquals("203.0.113.42", player.address());
	}

	@Test
	void readsSignedAndUnsignedPropertiesAlike() {
		ForwardedPlayer player = PlayerDataForwarding.verifyAndParse(vector(), SECRET);

		assertEquals(2, player.properties().size());

		ForwardedPlayer.Property textures = player.properties().get(0);
		assertEquals("textures", textures.name());
		assertEquals("sig-abc", textures.signature());

		// an unsigned property is written with a false flag and no signature at
		// all, not with an empty one - reading it as signed would desync the
		// cursor and corrupt every field after it
		ForwardedPlayer.Property unsigned = player.properties().get(1);
		assertEquals("unsigned", unsigned.name());
		assertNull(unsigned.signature());

		// non-ASCII survives: the payload is UTF-8 and the length prefix counts
		// bytes, not characters
		assertEquals("giá-trị-tiếng-việt", unsigned.value());
	}

	// ------------------------------------------------------------------ the security half

	/**
	 * The test this class exists for. Anything that can reach the backend's port
	 * could otherwise claim to be any player, including one holding an operator's
	 * UUID.
	 */
	@Test
	void aForgedSignatureIsRefused() {
		byte[] tampered = vector();
		tampered[0] ^= 0x01;

		LunaLegacyException refused = assertThrows(
			LunaLegacyException.class,
			() -> PlayerDataForwarding.verifyAndParse(tampered, SECRET)
		);

		assertTrue(refused.getMessage().contains("Chữ ký"), refused.getMessage());
	}

	/** The signature covers the payload, so editing the identity invalidates it. */
	@Test
	void anEditedPayloadIsRefused() {
		byte[] tampered = vector();
		tampered[tampered.length - 1] ^= 0x01;

		assertThrows(LunaLegacyException.class, () -> PlayerDataForwarding.verifyAndParse(tampered, SECRET));
	}

	@Test
	void theWrongSecretIsRefused() {
		assertThrows(
			LunaLegacyException.class,
			() -> PlayerDataForwarding.verifyAndParse(vector(), "01jO4qNY6QGbRUhS")
		);
	}

	/**
	 * A backend with no secret must refuse rather than trust the payload. Reading
	 * it unverified would make the whole exchange decorative.
	 */
	@Test
	void anAbsentSecretIsRefused() {
		assertThrows(LunaLegacyException.class, () -> PlayerDataForwarding.verifyAndParse(vector(), ""));
		assertThrows(LunaLegacyException.class, () -> PlayerDataForwarding.verifyAndParse(vector(), null));
	}

	@Test
	void aTruncatedAnswerIsRefused() {
		assertThrows(LunaLegacyException.class, () -> PlayerDataForwarding.verifyAndParse(new byte[0], SECRET));
		assertThrows(LunaLegacyException.class, () -> PlayerDataForwarding.verifyAndParse(new byte[16], SECRET));
	}

	/**
	 * A payload that passes the signature check can still be short - a proxy bug,
	 * or a secret shared with something that is not Velocity. It must fail as a
	 * refused login, not as an index out of bounds part-way through.
	 */
	@Test
	void aTruncatedPayloadFailsCleanly() {
		assertThrows(LunaLegacyException.class, () -> PlayerDataForwarding.parse(new byte[] { 0x01 }));
	}

	// ------------------------------------------------------------------ the request half

	@Test
	void theQueryAsksForTheRevisionWeCanRead() {
		assertEquals(1, PlayerDataForwarding.request().length);
		assertEquals(PlayerDataForwarding.MODERN_DEFAULT, PlayerDataForwarding.request()[0]);
	}

	private static byte[] vector() {
		InputStream stream = PlayerDataForwardingTest.class.getClassLoader().getResourceAsStream(FIXTURE);

		assertNotNull(stream, FIXTURE + " is missing from the test resources");

		try {
			String hex;

			try {
				hex = new String(LegacyHttp.drain(stream), StandardCharsets.UTF_8).trim();
			} finally {
				stream.close();
			}

			byte[] out = new byte[hex.length() / 2];

			for (int index = 0; index < out.length; index += 1) {
				out[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
			}

			return out;
		} catch (IOException failure) {
			throw new AssertionError("could not read " + FIXTURE, failure);
		}
	}
}
