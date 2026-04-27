package dev.belikhun.luna.core.api.compat;

import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class SimpleVoiceChatCompat {
	private static final Provider NOOP_PROVIDER = new Provider() {
		@Override
		public LunaImportedPlaceholderSupport.VoiceChatStatus playerStatus(UUID playerId) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
		}

		@Override
		public String playerGroup(UUID playerId) {
			return playerId == null ? "null" : "main";
		}
	};
	private static final AtomicReference<Provider> PROVIDER = new AtomicReference<>(NOOP_PROVIDER);

	private SimpleVoiceChatCompat() {
	}

	public static LunaImportedPlaceholderSupport.VoiceChatStatus playerStatus(UUID playerId) {
		return PROVIDER.get().playerStatus(playerId);
	}

	public static String playerGroup(UUID playerId) {
		return PROVIDER.get().playerGroup(playerId);
	}

	public static void installProvider(Provider provider) {
		PROVIDER.set(Objects.requireNonNullElse(provider, NOOP_PROVIDER));
	}

	public static void clearProvider(Provider provider) {
		if (provider == null) {
			PROVIDER.set(NOOP_PROVIDER);
			return;
		}

		PROVIDER.compareAndSet(provider, NOOP_PROVIDER);
	}

	public interface Provider {
		LunaImportedPlaceholderSupport.VoiceChatStatus playerStatus(UUID playerId);

		String playerGroup(UUID playerId);
	}
}
