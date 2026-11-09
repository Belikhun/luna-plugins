package dev.belikhun.luna.bpm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * The small wire between luna-bpm on the proxy and its bridge on each Paper backend.
 * <p>
 * The proxy owns models, crypto and selections, but only the backend knows a player's
 * entity id, world, and who is tracking whom. The bridge reports exactly those facts;
 * the proxy asks for a fresh report whenever a player lands on a backend.
 * <p>
 * Keep this file identical to paper/luna-bpm's copy.
 */
public final class BridgeProtocol {

	public static final String CHANNEL = "lunabpm:bridge";
	public static final int VERSION = 2;

	// backend -> proxy
	/** uuid subject, int entityId, utf world */
	public static final int STATE = 1;
	/** uuid viewer, uuid target */
	public static final int TRACK = 2;
	/** uuid viewer, uuid target */
	public static final int UNTRACK = 3;
	/** int bridge protocol version */
	public static final int HELLO = 4;
	/**
	 * int kind ({@link #KIND_PROJECTILE} or {@link #KIND_VEHICLE}), uuid receiver,
	 * uuid owner, int entityId: the receiver should draw that entity with the owner's
	 * model (an arrow the owner shot, a horse the owner rides).
	 */
	public static final int ENTITY_MODEL = 5;

	/** S2CSyncProjectileModelPacket id, reused as the kind */
	public static final int KIND_PROJECTILE = 16;
	/** S2CSyncVehicleModelPacket id, reused as the kind */
	public static final int KIND_VEHICLE = 22;

	// proxy -> backend
	/** ask for HELLO, the carrier's STATE and every TRACK pair involving the carrier */
	public static final int REQUEST = 10;
	/** int hand (0 main, 1 off) */
	public static final int SWING = 11;
	/** the carrier changed model: re-announce the vehicle they ride, if any */
	public static final int REFRESH_VEHICLE = 12;

	private BridgeProtocol() {
	}

	public static byte[] state(UUID subject, int entityId, String world) {
		return write(out -> {
			out.writeByte(STATE);
			writeUuid(out, subject);
			out.writeInt(entityId);
			out.writeUTF(world);
		});
	}

	public static byte[] pair(int type, UUID viewer, UUID target) {
		return write(out -> {
			out.writeByte(type);
			writeUuid(out, viewer);
			writeUuid(out, target);
		});
	}

	public static byte[] hello() {
		return write(out -> {
			out.writeByte(HELLO);
			out.writeInt(VERSION);
		});
	}

	public static byte[] entityModel(int kind, UUID receiver, UUID owner, int entityId) {
		return write(out -> {
			out.writeByte(ENTITY_MODEL);
			out.writeInt(kind);
			writeUuid(out, receiver);
			writeUuid(out, owner);
			out.writeInt(entityId);
		});
	}

	public static byte[] refreshVehicle() {
		return write(out -> out.writeByte(REFRESH_VEHICLE));
	}

	public static byte[] request() {
		return write(out -> out.writeByte(REQUEST));
	}

	public static byte[] swing(int hand) {
		return write(out -> {
			out.writeByte(SWING);
			out.writeInt(hand);
		});
	}

	public static DataInputStream reader(byte[] data) {
		return new DataInputStream(new ByteArrayInputStream(data));
	}

	public static void writeUuid(DataOutputStream out, UUID id) throws IOException {
		out.writeLong(id.getMostSignificantBits());
		out.writeLong(id.getLeastSignificantBits());
	}

	public static UUID readUuid(DataInputStream in) throws IOException {
		return new UUID(in.readLong(), in.readLong());
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] write(Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(48);
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}
}
