package dev.belikhun.luna.core.api.messaging;

import dev.belikhun.luna.core.api.exception.PluginMessagingException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PluginMessageReader {
	private final DataInputStream data;

	private PluginMessageReader(byte[] payload) {
		this.data = new DataInputStream(new ByteArrayInputStream(payload));
	}

	public static PluginMessageReader of(byte[] payload) {
		return new PluginMessageReader(payload);
	}

	public String readUtf() {
		try {
			return data.readUTF();
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc chuỗi UTF từ plugin message.", exception);
		}
	}

	public int readInt() {
		try {
			return data.readInt();
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc int từ plugin message.", exception);
		}
	}

	public short readShort() {
		try {
			return data.readShort();
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc short từ plugin message.", exception);
		}
	}

	public long readLong() {
		try {
			return data.readLong();
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc long từ plugin message.", exception);
		}
	}

	public boolean readBoolean() {
		try {
			return data.readBoolean();
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc boolean từ plugin message.", exception);
		}
	}

	public byte[] readBytes(int length) {
		byte[] buffer = new byte[length];
		try {
			data.readFully(buffer);
			return buffer;
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc bytes từ plugin message.", exception);
		}
	}

	public byte[] readShortPrefixedBytes() {
		int length = readShort() & 0xFFFF;
		return readBytes(length);
	}

	public String readRemainingString() {
		try {
			int available = data.available();
			return new String(readBytes(available), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new PluginMessagingException("Không thể đọc dữ liệu còn lại từ plugin message.", exception);
		}
	}

	public UUID readUuid() {
		return UUID.fromString(readUtf());
	}
}
