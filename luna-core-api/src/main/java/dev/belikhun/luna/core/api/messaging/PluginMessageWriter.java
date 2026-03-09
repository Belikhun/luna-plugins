package dev.belikhun.luna.core.api.messaging;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PluginMessageWriter {
	private final ByteArrayOutputStream output;
	private final DataOutputStream data;

	private PluginMessageWriter() {
		this.output = new ByteArrayOutputStream();
		this.data = new DataOutputStream(output);
	}

	public static PluginMessageWriter create() {
		return new PluginMessageWriter();
	}

	public PluginMessageWriter writeUtf(String value) {
		try {
			data.writeUTF(value);
			return this;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể ghi chuỗi UTF vào plugin message.", exception);
		}
	}

	public PluginMessageWriter writeInt(int value) {
		try {
			data.writeInt(value);
			return this;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể ghi int vào plugin message.", exception);
		}
	}

	public PluginMessageWriter writeShort(int value) {
		try {
			data.writeShort(value);
			return this;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể ghi short vào plugin message.", exception);
		}
	}

	public PluginMessageWriter writeLong(long value) {
		try {
			data.writeLong(value);
			return this;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể ghi long vào plugin message.", exception);
		}
	}

	public PluginMessageWriter writeBoolean(boolean value) {
		try {
			data.writeBoolean(value);
			return this;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể ghi boolean vào plugin message.", exception);
		}
	}

	public PluginMessageWriter writeUuid(UUID value) {
		return writeUtf(value.toString());
	}

	public PluginMessageWriter writeBytes(byte[] value) {
		try {
			data.write(value);
			return this;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể ghi bytes vào plugin message.", exception);
		}
	}

	public PluginMessageWriter writeShortPrefixed(byte[] value) {
		writeShort(value.length);
		writeBytes(value);
		return this;
	}

	public PluginMessageWriter writeStringBytes(String value) {
		return writeBytes(value.getBytes(StandardCharsets.UTF_8));
	}

	public byte[] toByteArray() {
		return output.toByteArray();
	}
}
