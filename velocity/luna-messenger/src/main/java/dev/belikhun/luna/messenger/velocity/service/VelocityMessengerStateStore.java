package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class VelocityMessengerStateStore {
	private static final int CURRENT_VERSION = 2;

	private final Path file;
	private final LunaLogger logger;

	public VelocityMessengerStateStore(Path file, LunaLogger logger) {
		this.file = file;
		this.logger = logger.scope("StateStore");
	}

	public VelocityMessengerRouter.PersistentState load() {
		if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
			return new VelocityMessengerRouter.PersistentState(Map.of(), Map.of(), Map.of(), Set.of());
		}

		try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
			int version = in.readInt();
			if (version < 1 || version > CURRENT_VERSION) {
				logger.warn("Bỏ qua state file vì version không khớp: " + version);
				return new VelocityMessengerRouter.PersistentState(Map.of(), Map.of(), Map.of(), Set.of());
			}

			Map<UUID, VelocityMessengerRouter.PersistedContext> contexts = readContexts(in);
			Map<UUID, UUID> replies = readReplies(in);
			Map<UUID, VelocityMessengerRouter.PersistedMute> mutes = readMutes(in);
			Set<UUID> seenPlayers = version >= 2 ? readSeenPlayers(in) : Set.of();
			logger.audit("Đã nạp state: contexts=" + contexts.size() + ", replies=" + replies.size() + ", mutes=" + mutes.size() + ", seenPlayers=" + seenPlayers.size());
			return new VelocityMessengerRouter.PersistentState(contexts, replies, mutes, seenPlayers);
		} catch (Exception exception) {
			logger.warn("Không thể nạp state messenger: " + exception.getMessage());
			return new VelocityMessengerRouter.PersistentState(Map.of(), Map.of(), Map.of(), Set.of());
		}
	}

	public void save(VelocityMessengerRouter.PersistentState state) {
		if (file == null || state == null) {
			return;
		}

		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (IOException exception) {
			logger.warn("Không thể tạo thư mục state: " + exception.getMessage());
			return;
		}

		try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
			out.writeInt(CURRENT_VERSION);
			writeContexts(out, state.contexts());
			writeReplies(out, state.lastReplyByPlayer());
			writeMutes(out, state.mutedPlayers());
			writeSeenPlayers(out, state.seenPlayers());
			logger.audit("Đã lưu state: contexts=" + state.contexts().size() + ", replies=" + state.lastReplyByPlayer().size() + ", mutes=" + state.mutedPlayers().size() + ", seenPlayers=" + state.seenPlayers().size());
		} catch (Exception exception) {
			logger.warn("Không thể lưu state messenger: " + exception.getMessage());
		}
	}

	private Set<UUID> readSeenPlayers(DataInputStream in) throws IOException {
		int size = in.readInt();
		Set<UUID> seenPlayers = new HashSet<>(Math.max(0, size));
		for (int index = 0; index < size; index++) {
			seenPlayers.add(UUID.fromString(in.readUTF()));
		}
		return seenPlayers;
	}

	private Map<UUID, VelocityMessengerRouter.PersistedContext> readContexts(DataInputStream in) throws IOException {
		int size = in.readInt();
		Map<UUID, VelocityMessengerRouter.PersistedContext> contexts = new HashMap<>(Math.max(0, size));
		for (int index = 0; index < size; index++) {
			UUID playerId = UUID.fromString(in.readUTF());
			String type = in.readUTF();
			boolean hasTargetId = in.readBoolean();
			UUID targetId = hasTargetId ? UUID.fromString(in.readUTF()) : null;
			String targetName = in.readUTF();
			contexts.put(playerId, new VelocityMessengerRouter.PersistedContext(type, targetId, targetName.isBlank() ? null : targetName));
		}
		return contexts;
	}

	private Map<UUID, UUID> readReplies(DataInputStream in) throws IOException {
		int size = in.readInt();
		Map<UUID, UUID> replies = new HashMap<>(Math.max(0, size));
		for (int index = 0; index < size; index++) {
			UUID senderId = UUID.fromString(in.readUTF());
			UUID targetId = UUID.fromString(in.readUTF());
			replies.put(senderId, targetId);
		}
		return replies;
	}

	private Map<UUID, VelocityMessengerRouter.PersistedMute> readMutes(DataInputStream in) throws IOException {
		int size = in.readInt();
		Map<UUID, VelocityMessengerRouter.PersistedMute> mutes = new HashMap<>(Math.max(0, size));
		for (int index = 0; index < size; index++) {
			UUID playerId = UUID.fromString(in.readUTF());
			String actor = in.readUTF();
			String reason = in.readUTF();
			long mutedAt = in.readLong();
			boolean hasExpires = in.readBoolean();
			Long expires = hasExpires ? in.readLong() : null;
			mutes.put(playerId, new VelocityMessengerRouter.PersistedMute(actor, reason, mutedAt, expires));
		}
		return mutes;
	}

	private void writeContexts(DataOutputStream out, Map<UUID, VelocityMessengerRouter.PersistedContext> contexts) throws IOException {
		out.writeInt(contexts.size());
		for (Map.Entry<UUID, VelocityMessengerRouter.PersistedContext> entry : contexts.entrySet()) {
			VelocityMessengerRouter.PersistedContext context = entry.getValue();
			out.writeUTF(entry.getKey().toString());
			out.writeUTF(context.type() == null ? "" : context.type());
			out.writeBoolean(context.directTargetId() != null);
			if (context.directTargetId() != null) {
				out.writeUTF(context.directTargetId().toString());
			}
			out.writeUTF(context.directTargetName() == null ? "" : context.directTargetName());
		}
	}

	private void writeReplies(DataOutputStream out, Map<UUID, UUID> replies) throws IOException {
		out.writeInt(replies.size());
		for (Map.Entry<UUID, UUID> entry : replies.entrySet()) {
			out.writeUTF(entry.getKey().toString());
			out.writeUTF(entry.getValue().toString());
		}
	}

	private void writeMutes(DataOutputStream out, Map<UUID, VelocityMessengerRouter.PersistedMute> mutes) throws IOException {
		out.writeInt(mutes.size());
		for (Map.Entry<UUID, VelocityMessengerRouter.PersistedMute> entry : mutes.entrySet()) {
			VelocityMessengerRouter.PersistedMute mute = entry.getValue();
			out.writeUTF(entry.getKey().toString());
			out.writeUTF(mute.actor() == null ? "" : mute.actor());
			out.writeUTF(mute.reason() == null ? "" : mute.reason());
			out.writeLong(mute.mutedAtEpochMs());
			out.writeBoolean(mute.expiresAtEpochMs() != null);
			if (mute.expiresAtEpochMs() != null) {
				out.writeLong(mute.expiresAtEpochMs());
			}
		}
	}

	private void writeSeenPlayers(DataOutputStream out, Set<UUID> seenPlayers) throws IOException {
		out.writeInt(seenPlayers.size());
		for (UUID playerId : seenPlayers) {
			out.writeUTF(playerId.toString());
		}
	}
}
