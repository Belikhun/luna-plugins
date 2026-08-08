package dev.belikhun.luna.core.api.messenger;

import dev.belikhun.luna.core.api.messaging.PluginMessageReader;
import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MessengerCodec {
	private MessengerCodec() {
	}

	public static void writeStringMap(PluginMessageWriter writer, Map<String, String> values) {
		if (values == null || values.isEmpty()) {
			writer.writeInt(0);
			return;
		}

		writer.writeInt(values.size());
		for (Map.Entry<String, String> entry : values.entrySet()) {
			writer.writeUtf(entry.getKey());
			writer.writeUtf(entry.getValue() == null ? "" : entry.getValue());
		}
	}

	public static Map<String, String> readStringMap(PluginMessageReader reader) {
		int size = reader.readInt();
		Map<String, String> values = new LinkedHashMap<>(Math.max(size, 0));
		for (int index = 0; index < size; index++) {
			String key = reader.readUtf();
			String value = reader.readUtf();
			values.put(key, value);
		}
		return values;
	}
}
