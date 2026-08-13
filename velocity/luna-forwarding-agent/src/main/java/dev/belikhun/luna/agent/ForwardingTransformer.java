package dev.belikhun.luna.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Two constants, rewritten as Velocity loads.
 *
 * Both edits are the same operation - point a `GETSTATIC` at a different
 * `ProtocolVersion` constant - which is why there is no branch surgery or method
 * injection here, and why the whole thing is small enough to audit.
 *
 * **1. `StateRegistry`** maps the two login-plugin packets from `MINECRAFT_1_13`,
 * so at protocol 340 the proxy can neither decode the query a legacy backend
 * sends nor encode its answer. Lowering them to `MINECRAFT_1_7_2` makes the
 * exchange decodable on the proxy-to-backend leg. The client leg is unaffected:
 * these ids do not exist in a pre-1.13 vanilla client's login state, and nothing
 * sends them there.
 *
 * **2. `HandshakeSessionHandler#handle`** refuses every pre-1.13 client while
 * modern forwarding is on. Rewriting its `MINECRAFT_1_13` to `MINECRAFT_1_7_2`
 * turns the test into `protocol < 1.7.2`, which is never true, so the gate stops
 * firing. Removing it loses nothing: the same guarantee is enforced per
 * connection where it matters, in `LoginSessionHandler`, which refuses any
 * backend that reached login success without asking for forwarding data.
 *
 * The second edit is found by context, not by location. `HandshakeSessionHandler`
 * references `MINECRAFT_1_13` twice, and the other one is how Velocity recognises
 * a legacy Forge client - rewriting the class blindly would quietly break Forge
 * 1.8-1.12 detection while looking like it worked. The gate is picked out by the
 * `PlayerInfoForwarding.MODERN` comparison it is paired with, which is the thing
 * that actually defines it.
 */
final class ForwardingTransformer implements ClassFileTransformer {
	private static final String STATE_REGISTRY = "com/velocitypowered/proxy/protocol/StateRegistry";
	private static final String HANDSHAKE_HANDLER =
		"com/velocitypowered/proxy/connection/client/HandshakeSessionHandler";

	private static final String PROTOCOL_VERSION = "com/velocitypowered/api/network/ProtocolVersion";
	private static final String GATED_VERSION = "MINECRAFT_1_13";
	private static final String LOWERED_VERSION = "MINECRAFT_1_7_2";

	/** The packets whose registrations carry the version we lower. */
	private static final String[] LOGIN_PLUGIN_PACKETS = {
		"com/velocitypowered/proxy/protocol/packet/LoginPluginResponsePacket",
		"com/velocitypowered/proxy/protocol/packet/LoginPluginMessagePacket",
	};

	/**
	 * How far past a packet's class literal the version constant may sit.
	 *
	 * The registration is one expression - `register(Packet.class, Packet::new,
	 * map(id, version, false))` - so the constant is a handful of instructions
	 * away. A generous bound still cannot reach the next registration, and a
	 * miss throws rather than silently searching on.
	 */
	private static final int VERSION_SEARCH_WINDOW = 40;

	private static final String PLAYER_INFO_FORWARDING = "com/velocitypowered/proxy/config/PlayerInfoForwarding";
	private static final String MODERN_MODE = "MODERN";

	/** How far above the version test the forwarding-mode comparison may sit. */
	private static final int GATE_LOOKBACK_WINDOW = 20;

	private boolean stateRegistryPatched;
	private boolean handshakePatched;

	@Override
	public byte[] transform(
		ClassLoader loader,
		String className,
		Class<?> classBeingRedefined,
		ProtectionDomain protectionDomain,
		byte[] classfileBuffer
	) {
		if (className == null) {
			return null;
		}

		try {
			// the LOGIN constant has a body, so javac compiles it to an inner class
			// of StateRegistry rather than to the enum itself
			if (className.startsWith(STATE_REGISTRY)) {
				return patchStateRegistry(className, classfileBuffer);
			}

			if (className.equals(HANDSHAKE_HANDLER)) {
				return patchHandshakeHandler(classfileBuffer);
			}
		} catch (RuntimeException | LinkageError failure) {
			// Reported, not fatal, and not rethrown - the JVM swallows a transformer's
			// exception and loads the class unpatched anyway, so throwing would only
			// look decisive.
			//
			// Failing closed is the right outcome here and it is already what happens:
			// an unpatched proxy keeps refusing pre-1.13 clients, so the 1.12.2 line
			// goes unavailable and every other backend is untouched. Halting the proxy
			// instead would take the whole cluster down over one legacy server, which
			// is far worse than the thing it would be protecting against.
			System.err.println("[luna-agent] KHÔNG vá được " + className + ": " + failure);
			System.err.println("[luna-agent] Modern forwarding cho client < 1.13 sẽ KHÔNG hoạt động.");
		}

		return null;
	}

	/** @return the rewritten class, or null when this one carries no registration */
	private byte[] patchStateRegistry(String className, byte[] original) {
		ClassNode node = read(original);
		int patched = 0;

		for (MethodNode method : node.methods) {
			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (!isClassLiteral(instruction, LOGIN_PLUGIN_PACKETS)) {
					continue;
				}

				patched += lowerFollowingVersion(instruction, describe(instruction));
			}
		}

		if (patched == 0) {
			return null;
		}

		stateRegistryPatched = true;
		announce(className + ": " + patched + " packet mapping(s)");

		return write(node);
	}

	/**
	 * Find the forwarding gate by what it *is*, not by where it lives.
	 *
	 * Matching on a method name would be wrong twice over: the gate sits in a
	 * private `handleLogin` rather than in `handle`, and the class's other
	 * `MINECRAFT_1_13` - the legacy-Forge check - is in a method taking the same
	 * argument type, so a signature match can select the wrong one. Both names
	 * are private and free to change.
	 *
	 * What cannot drift without the gate itself changing meaning is the pairing:
	 * the version test is guarded by a comparison against
	 * `PlayerInfoForwarding.MODERN` a few instructions earlier. Nothing else in
	 * the class puts those two constants together.
	 */
	private byte[] patchHandshakeHandler(byte[] original) {
		ClassNode node = read(original);
		int patched = 0;

		for (MethodNode method : node.methods) {
			for (AbstractInsnNode instruction : method.instructions.toArray()) {
				if (!isGatedVersion(instruction) || !precededByModernForwarding(instruction)) {
					continue;
				}

				((FieldInsnNode) instruction).name = LOWERED_VERSION;
				patched += 1;
			}
		}

		if (patched != 1) {
			throw new IllegalStateException(
				"mong đợi đúng 1 cổng " + GATED_VERSION + " đi kèm PlayerInfoForwarding.MODERN, thấy " + patched
			);
		}

		handshakePatched = true;
		announce(HANDSHAKE_HANDLER + ": cổng chặn client < 1.13 đã được gỡ");

		return write(node);
	}

	/** Whether a MODERN-forwarding comparison sits just above this instruction. */
	private static boolean precededByModernForwarding(AbstractInsnNode start) {
		AbstractInsnNode instruction = start;

		for (int step = 0; step < GATE_LOOKBACK_WINDOW && instruction != null; step += 1) {
			instruction = instruction.getPrevious();

			if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.GETSTATIC) {
				continue;
			}

			FieldInsnNode field = (FieldInsnNode) instruction;

			if (field.owner.equals(PLAYER_INFO_FORWARDING) && field.name.equals(MODERN_MODE)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Lower the first gated version constant after a packet's class literal.
	 *
	 * @return 1, always: a registration with no version constant means the shape
	 *         changed, and that throws rather than returning 0 and looking fine
	 */
	private int lowerFollowingVersion(AbstractInsnNode start, String what) {
		AbstractInsnNode instruction = start;

		for (int step = 0; step < VERSION_SEARCH_WINDOW && instruction != null; step += 1) {
			instruction = instruction.getNext();

			if (isGatedVersion(instruction)) {
				((FieldInsnNode) instruction).name = LOWERED_VERSION;

				return 1;
			}
		}

		throw new IllegalStateException("không thấy " + GATED_VERSION + " sau khi đăng ký " + what);
	}

	private static boolean isClassLiteral(AbstractInsnNode instruction, String[] internalNames) {
		if (!(instruction instanceof LdcInsnNode)) {
			return false;
		}

		Object constant = ((LdcInsnNode) instruction).cst;

		if (!(constant instanceof Type)) {
			return false;
		}

		String internalName = ((Type) constant).getInternalName();

		for (String candidate : internalNames) {
			if (candidate.equals(internalName)) {
				return true;
			}
		}

		return false;
	}

	private static boolean isGatedVersion(AbstractInsnNode instruction) {
		if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.GETSTATIC) {
			return false;
		}

		FieldInsnNode field = (FieldInsnNode) instruction;

		return field.owner.equals(PROTOCOL_VERSION) && field.name.equals(GATED_VERSION);
	}

	private static String describe(AbstractInsnNode instruction) {
		return ((Type) ((LdcInsnNode) instruction).cst).getClassName();
	}

	private void announce(String what) {
		System.out.println("[luna-agent] Đã vá " + what + ".");

		if (stateRegistryPatched && handshakePatched) {
			System.setProperty(LunaForwardingAgent.READY_PROPERTY, "true");
			System.out.println("[luna-agent] Modern forwarding đã sẵn sàng cho client < 1.13.");
		}
	}

	private static ClassNode read(byte[] original) {
		ClassNode node = new ClassNode();

		new ClassReader(original).accept(node, 0);

		return node;
	}

	/**
	 * Written without frame or maxs recomputation on purpose: swapping one field
	 * reference for another of the same type changes neither, and COMPUTE_FRAMES
	 * would need to load Velocity's classes to resolve them - during Velocity's
	 * own class loading.
	 */
	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);

		node.accept(writer);

		return writer.toByteArray();
	}
}
