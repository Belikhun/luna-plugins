package dev.belikhun.luna.core.api.compat;

import dev.belikhun.luna.core.api.placeholder.LunaImportedPlaceholderSupport;

import java.lang.reflect.Method;
import java.util.UUID;

public final class SimpleVoiceChatCompat {
	private SimpleVoiceChatCompat() {
	}

	public static LunaImportedPlaceholderSupport.VoiceChatStatus playerStatus(UUID playerId) {
		if (playerId == null) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
		}

		Object serverApi = serverApi();
		if (serverApi == null) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
		}

		Object connection = invoke(serverApi, "getConnectionOf", new Class<?>[] {UUID.class}, playerId);
		if (connection == null) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.DISCONNECTED;
		}

		Boolean installed = invokeBoolean(connection, "isInstalled");
		if (Boolean.FALSE.equals(installed)) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.NOT_INSTALLED;
		}

		Boolean disabled = invokeBoolean(connection, "isDisabled");
		if (Boolean.TRUE.equals(disabled)) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.MUTED;
		}

		Boolean connected = invokeBoolean(connection, "isConnected");
		if (Boolean.TRUE.equals(connected)) {
			return LunaImportedPlaceholderSupport.VoiceChatStatus.CONNECTED;
		}

		return LunaImportedPlaceholderSupport.VoiceChatStatus.UNKNOWN;
	}

	public static String playerGroup(UUID playerId) {
		if (playerId == null) {
			return "null";
		}

		Object serverApi = serverApi();
		if (serverApi == null) {
			return "null";
		}

		Object connection = invoke(serverApi, "getConnectionOf", new Class<?>[] {UUID.class}, playerId);
		if (connection == null) {
			return "null";
		}

		Object group = invoke(connection, "getGroup", new Class<?>[0]);
		if (group == null) {
			return "main";
		}

		Object groupName = invoke(group, "getName", new Class<?>[0]);
		if (groupName instanceof String value && !value.isBlank()) {
			return value;
		}

		return "main";
	}

	private static Object serverApi() {
		try {
			Class<?> apiClass = Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl");
			Method instanceMethod = apiClass.getMethod("instance");
			instanceMethod.setAccessible(true);
			return instanceMethod.invoke(null);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}

	private static Boolean invokeBoolean(Object target, String methodName) {
		Object value = invoke(target, methodName, new Class<?>[0]);
		return value instanceof Boolean booleanValue ? booleanValue : null;
	}

	private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
		if (target == null) {
			return null;
		}

		try {
			Method method = target.getClass().getMethod(methodName, parameterTypes);
			method.setAccessible(true);
			return method.invoke(target, args);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			return null;
		}
	}
}
