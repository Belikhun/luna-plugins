package dev.belikhun.luna.legacy.heartbeat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins this module's encoder to the one the rest of the cluster runs.
 *
 * Nothing in the type system connects a Java 8 backend to the Java 21 proxy it
 * reports to; the only contract is the bytes. `heartbeat-vectors.tsv` holds output
 * captured from `luna-core-api`'s encoder running under a JDK, and these tests fail
 * if this module stops reproducing it - which is what a downgrade is most likely to
 * break silently, since a wrong field name or a reordered map still encodes fine and
 * simply registers the backend wrong.
 *
 * The generator that produced the fixture is kept beside it as
 * `heartbeat-vectors-generator.java.txt`, so regenerating after a deliberate protocol
 * change is a matter of recompiling it against the current luna-core-api jar rather
 * than reconstructing what the inputs were.
 *
 * The same approach the pumpkin port takes for its Rust codec
 * (`pumpkin/luna-core-api/tests/java_compat.rs`).
 */
class HeartbeatFormCodecCompatTest {
	private static final Map<String, String> VECTORS = loadVectors();

	@Test
	void protocolVersionMatchesTheClusterItReportsTo() {
		assertEquals(VECTORS.get("PROTOCOL"), String.valueOf(HeartbeatFormCodec.PROTOCOL_VERSION));
	}

	@Test
	void statsEncodeByteForByteLikeTheModernApi() {
		BackendHeartbeatStats stats = sampleStats();

		assertEquals(
			VECTORS.get("STATS"),
			HeartbeatFormCodec.encodeToString(HeartbeatFormCodec.encodeStats(stats))
		);
	}

	@Test
	void rowsEncodeByteForByteLikeTheModernApi() {
		byte[] encoded = HeartbeatFormCodec.encodeRows(
			sampleRows(),
			22232L,
			"ed70dc3e-01a6-4525-8eba-49f20b40e7bf",
			false,
			"forge12test",
			new BackendMetadata("forge12test", "FORGE 1.12.2", "#5FE2C5", "dev")
		);

		assertEquals(VECTORS.get("ROWS"), new String(encoded, StandardCharsets.UTF_8));
	}

	/**
	 * The fixture was produced by the modern encoder, so decoding it here proves both
	 * directions against a payload this module did not write.
	 */
	@Test
	void decodesAPayloadTheModernApiWrote() {
		byte[] body = VECTORS.get("ROWS").getBytes(StandardCharsets.UTF_8);
		HeartbeatSnapshotPayload payload = HeartbeatFormCodec.decodeSnapshotPayload(body);

		assertEquals(HeartbeatFormCodec.PROTOCOL_VERSION, payload.protocol());
		assertEquals("ed70dc3e-01a6-4525-8eba-49f20b40e7bf", payload.epoch());
		assertEquals(22232L, payload.revision());
		assertEquals(1, payload.rows().size());

		BackendStatusRow row = payload.rows().get(0);

		assertEquals("forge12test", row.serverName());
		assertTrue(row.self());
		assertEquals(32566, row.status().stats().serverPort());
		assertEquals(19.997, row.status().stats().tps(), 0D);
	}

	/**
	 * The display name carries markup and Vietnamese diacritics on purpose. Java 8's
	 * `URLEncoder` has no Charset overload, and the deprecated fallback encodes with
	 * the platform default - which round-trips fine on a UTF-8 developer machine and
	 * corrupts every accented MOTD on a server started under a different locale.
	 */
	@Test
	void nonAsciiSurvivesTheRoundTrip() {
		String display = "<gradient:#5FE2C5:#9BC1F9>FORGE 1.12.2</gradient> · Máy chủ";
		Map<String, String> decoded = HeartbeatFormCodec.decode(
			HeartbeatFormCodec.encode(single("server_display", display))
		);

		assertEquals(display, decoded.get("server_display"));
	}

	@Test
	void statsSurviveTheirOwnRoundTrip() {
		BackendHeartbeatStats stats = sampleStats();
		BackendHeartbeatStats returned = HeartbeatFormCodec.decodeStats(HeartbeatFormCodec.encodeStats(stats));

		assertEquals(stats, returned);
	}

	private static BackendHeartbeatStats sampleStats() {
		return new BackendHeartbeatStats(
			"forge",
			"1.12.2",
			32566,
			123456789L,
			19.997,
			3,
			64,
			"A Luna Minecraft Server",
			false,
			42.550040849673195,
			0.0,
			395202048L,
			1752281600L,
			2147483648L,
			1L
		);
	}

	private static List<BackendStatusRow> sampleRows() {
		BackendServerStatus status = new BackendServerStatus(
			"forge12test",
			"<gradient:#5FE2C5:#9BC1F9>FORGE 1.12.2</gradient> · Máy chủ",
			"#5FE2C5",
			true,
			1786345809152L,
			sampleStats()
		);

		List<BackendStatusRow> rows = new ArrayList<BackendStatusRow>();

		rows.add(new BackendStatusRow(status, 22232L, true));

		return rows;
	}

	private static Map<String, String> single(String key, String value) {
		Map<String, String> out = new HashMap<String, String>();

		out.put(key, value);

		return out;
	}

	private static Map<String, String> loadVectors() {
		Map<String, String> out = new HashMap<String, String>();
		InputStream stream = HeartbeatFormCodecCompatTest.class
			.getClassLoader()
			.getResourceAsStream("heartbeat-vectors.tsv");

		assertNotNull(stream, "heartbeat-vectors.tsv is missing from the test resources");

		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;

			while ((read = stream.read(chunk)) > 0) {
				buffer.write(chunk, 0, read);
			}

			for (String line : new String(buffer.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
				String[] parts = line.split("\t", 2);

				if (parts.length == 2) {
					out.put(parts[0], parts[1]);
				}
			}
		} catch (IOException failure) {
			throw new IllegalStateException("could not read the heartbeat fixtures", failure);
		} finally {
			close(stream);
		}

		assertTrue(out.keySet().containsAll(Arrays.asList("STATS", "ROWS", "PROTOCOL")), "fixture is incomplete");

		return out;
	}

	private static void close(InputStream stream) {
		try {
			stream.close();
		} catch (IOException ignored) {
			// nothing useful to do while already returning fixtures
		}
	}
}
