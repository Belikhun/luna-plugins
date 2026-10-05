package dev.belikhun.luna.core.api.placeholder;

import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.VoiceChatStatus;
import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport.WorldKind;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A host whose players are plain strings and whose facts are fixed. */
final class TestHost implements PlaceholderHost<String> {
	final Map<String, String> foreignValues = new HashMap<>();

	@Override
	public UUID idOf(String player) {
		return UUID.nameUUIDFromBytes(player.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public String nameOf(String player) {
		return player;
	}

	@Override
	public ServerSample sampleServer() {
		return new ServerSample(
			"survival",
			"mayphatdien",
			"Sinh tồn",
			"#87ff08",
			"Paper 1.21.11",
			7,
			60,
			false,
			19.87D,
			12.345D,
			3_725_000L,
			42.55D,
			12.3D,
			2L * 1024L * 1024L * 1024L,
			8L * 1024L * 1024L * 1024L,
			1234,
			456,
			789,
			17L,
			ServerSample.indexWorlds(List.of(
				new WorldWeather("survival", true, false, 20L * 90L),
				new WorldWeather("survival_nether", false, false, 0L)
			)),
			System.currentTimeMillis()
		);
	}

	@Override
	public PlayerSample samplePlayer(String player) {
		return PlayerSample.builder(idOf(player), player)
			.displayName("<gold>" + player + "</gold>")
			.ping(45)
			.world("survival_nether", WorldKind.NETHER, "minecraft:the_nether")
			.position(10, 64, -20)
			.health(17.5D, 20D)
			.level(37)
			.permissions("<red>[Admin]</red>", "", "admin", "Admin")
			.voiceChat(VoiceChatStatus.CONNECTED, "main")
			.build();
	}

	@Override
	public String foreign(String player, PlaceholderRef ref) {
		return foreignValues.get(ref.normalizedIdentifier());
	}
}
