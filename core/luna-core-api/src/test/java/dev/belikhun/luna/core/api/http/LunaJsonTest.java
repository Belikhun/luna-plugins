package dev.belikhun.luna.core.api.http;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LunaJsonTest {
	@Test
	void writesOrderedObjectsAndArrays() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", "lobby");
		payload.put("online", true);
		payload.put("players", 12);
		payload.put("servers", List.of("lobby", "survival"));

		assertEquals(
			"{\"name\":\"lobby\",\"online\":true,\"players\":12,\"servers\":[\"lobby\",\"survival\"]}",
			LunaJson.write(payload)
		);
	}

	@Test
	void escapesQuotesBackslashesAndControlCharacters() {
		assertEquals("\"say \\\"hi\\\"\"", LunaJson.write("say \"hi\""));
		assertEquals("\"C:\\\\servers\"", LunaJson.write("C:\\servers"));
		assertEquals("\"line\\nbreak\"", LunaJson.write("line\nbreak"));
		assertEquals("\"bell\\u0007\"", LunaJson.write("bell\u0007"));
	}

	@Test
	void escapesJavascriptLineSeparators() {
		// U+2028/2029 are valid JSON but break naive JS consumers that eval the body
		String separator = String.valueOf((char) 0x2028);

		assertEquals("\"a\\u2028b\"", LunaJson.write("a" + separator + "b"));
	}

	@Test
	void writesNonFiniteDoublesAsNull() {
		assertEquals("null", LunaJson.write(Double.NaN));
		assertEquals("null", LunaJson.write(Double.POSITIVE_INFINITY));
		assertEquals("0.0", LunaJson.write(LunaJson.round(Double.NaN)));
	}

	@Test
	void roundsToTwoDecimals() {
		assertEquals(19.99D, LunaJson.round(19.9912D));
		assertEquals(31.55D, LunaJson.round(31.5549D));
		assertEquals(0D, LunaJson.round(Double.NEGATIVE_INFINITY));
	}

	@Test
	void buildersNestAndSkipBlankOptionalFields() {
		String json = LunaJson.write(LunaJson.obj()
			.put("id", "lobby")
			.putIfPresent("accentColor", "")
			.putIfPresent("displayName", "Lobby")
			.putIfPresent("missing", null)
			.putRounded("tps", 19.9987D)
			.put("history", LunaJson.arr().add(1).add(2)));

		assertEquals("{\"id\":\"lobby\",\"displayName\":\"Lobby\",\"tps\":20.0,\"history\":[1,2]}", json);
	}

	@Test
	void successEnvelopeWrapsPayloadUnderData() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("onlineCount", 3);

		HttpResponse response = LunaJson.envelope(200, payload, System.nanoTime());
		String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);

		assertEquals(200, response.status());
		assertTrue(body.startsWith("{\"success\":true,"), body);
		assertTrue(body.endsWith("\"data\":{\"onlineCount\":3}}"), body);
	}

	@Test
	void errorEnvelopeMergesFieldsAndReportsFailure() {
		HttpResponse response = LunaJson.error(404, "player not connected: nobody");
		String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);

		assertEquals(404, response.status());
		assertTrue(body.contains("\"success\":false"), body);
		assertTrue(body.contains("\"error\":\"player not connected: nobody\""), body);
	}
}
