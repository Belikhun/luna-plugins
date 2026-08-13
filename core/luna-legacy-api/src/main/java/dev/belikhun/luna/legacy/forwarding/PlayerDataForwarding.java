package dev.belikhun.luna.legacy.forwarding;

import dev.belikhun.luna.legacy.exception.LunaLegacyException;
import dev.belikhun.luna.legacy.string.Strings;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Velocity's modern player-info forwarding, implemented against the proxy's own
 * wire format.
 *
 * A backend behind Velocity gets no useful identity for free: every connection
 * arrives from the proxy's address, and with authentication off the name is all
 * there is. Modern forwarding fixes that by having the backend *ask* during
 * login, on the `velocity:player_info` channel, and the proxy answer with the
 * player's real address, UUID and signed profile properties.
 *
 * **The signature is the whole security model.** The answer is HMAC-SHA256 over
 * the payload, keyed with the forwarding secret both sides share. Without the
 * check, anything that can reach the backend's port could claim to be any
 * player, including one with an operator's UUID - so verification happens here,
 * before a single field is read, and a failure is a refused login rather than a
 * warning. The comparison is constant-time (`MessageDigest.isEqual`), because a
 * byte-by-byte compare that returns early leaks how much of a forged signature
 * was right and makes forging one a matter of patience.
 *
 * The format is Velocity's, read from its `PlayerDataForwarding`:
 *
 * ```
 * signature : 32 bytes HMAC-SHA256 over everything below
 * version   : VarInt
 * address   : String   the player's real remote address
 * uuid      : 2 longs  most significant first
 * username  : String
 * properties: VarInt count, then per property
 *             name String, value String, hasSignature Boolean, [signature String]
 * ```
 */
public final class PlayerDataForwarding {
	/** The login-plugin channel the exchange happens on. */
	public static final String CHANNEL = "velocity:player_info";

	private static final String ALGORITHM = "HmacSHA256";

	/** HMAC-SHA256 output, and so the length of the prefix on every answer. */
	public static final int SIGNATURE_LENGTH = 32;

	/**
	 * The revision we ask for, and the only one that means anything here.
	 *
	 * Later revisions carry the player's signed chat key, which arrived in 1.19
	 * and cannot exist for a client this old. Velocity clamps a request to what
	 * the player's protocol supports anyway, so asking for more would be answered
	 * with this regardless; asking for exactly this states the intent.
	 */
	public static final int MODERN_DEFAULT = 1;

	private PlayerDataForwarding() {
	}

	/**
	 * The body of the query a backend sends to open the exchange: a single byte
	 * naming the highest revision it understands.
	 */
	public static byte[] request() {
		return new byte[] { (byte) MODERN_DEFAULT };
	}

	/**
	 * Verify the proxy's answer and read the player out of it.
	 *
	 * @param answer the whole response body: signature followed by payload
	 * @param secret the forwarding secret, shared with the proxy
	 * @return who the proxy says is connecting
	 * @throws LunaLegacyException if the secret is missing, the answer is
	 *         truncated, or the signature does not match - none of which are
	 *         recoverable, because each one means the identity is unproven
	 */
	public static ForwardedPlayer verifyAndParse(byte[] answer, String secret) {
		if (Strings.isBlank(secret)) {
			throw new LunaLegacyException("Thiếu forwarding secret; không thể xác thực dữ liệu từ proxy.");
		}

		if (answer == null || answer.length < SIGNATURE_LENGTH) {
			throw new LunaLegacyException("Proxy trả lời forwarding quá ngắn để chứa chữ ký.");
		}

		byte[] signature = new byte[SIGNATURE_LENGTH];
		System.arraycopy(answer, 0, signature, 0, SIGNATURE_LENGTH);

		byte[] payload = new byte[answer.length - SIGNATURE_LENGTH];
		System.arraycopy(answer, SIGNATURE_LENGTH, payload, 0, payload.length);

		if (!MessageDigest.isEqual(signature, sign(payload, secret))) {
			throw new LunaLegacyException(
				"Chữ ký forwarding không khớp; dữ liệu người chơi không đến từ proxy đã cấu hình."
			);
		}

		return parse(payload);
	}

	/**
	 * Read an already-verified payload.
	 *
	 * Separate from the verification on purpose: this must never be reachable
	 * from the network path without the signature check in front of it, so making
	 * it its own step is what keeps a caller from quietly skipping one.
	 */
	static ForwardedPlayer parse(byte[] payload) {
		ProtocolBuffer buffer = new ProtocolBuffer(payload);

		int version = buffer.readVarInt();
		String address = buffer.readString();
		UUID uniqueId = buffer.readUuid();
		String username = buffer.readString();

		int count = buffer.readVarInt();

		if (count < 0 || count > 64) {
			throw new LunaLegacyException("Số property không hợp lệ trong dữ liệu forwarding: " + count);
		}

		List<ForwardedPlayer.Property> properties = new ArrayList<ForwardedPlayer.Property>(count);

		for (int index = 0; index < count; index += 1) {
			String name = buffer.readString();
			String value = buffer.readString();
			String propertySignature = buffer.readBoolean() ? buffer.readString() : null;

			properties.add(new ForwardedPlayer.Property(name, value, propertySignature));
		}

		// trailing bytes are not an error: a proxy answering a newer revision than
		// we asked for appends fields we have no use for, and refusing the login
		// over data we chose not to read would be a self-inflicted outage
		return new ForwardedPlayer(version, address, uniqueId, username, properties);
	}

	private static byte[] sign(byte[] payload, String secret) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);

			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));

			return mac.doFinal(payload);
		} catch (GeneralSecurityException failure) {
			throw new LunaLegacyException("Không tính được HMAC cho dữ liệu forwarding.", failure);
		}
	}
}
