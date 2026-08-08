package dev.belikhun.luna.auth.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;

public final class Pbkdf2PasswordHasher {
	private static final String ALGORITHM = "PBKDF2WithHmacSHA512";
	private static final SecureRandom SECURE_RANDOM = createSecureRandom();
	private final int iterations;
	private final int saltLength;
	private final int keyLengthBits;

	public Pbkdf2PasswordHasher(int iterations, int saltLength, int keyLengthBits) {
		this.iterations = Math.max(50_000, iterations);
		this.saltLength = Math.max(16, saltLength);
		this.keyLengthBits = Math.max(128, keyLengthBits);
	}

	public String hash(String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalArgumentException("Password cannot be null or blank.");
		}

		byte[] salt = new byte[saltLength];
		SECURE_RANDOM.nextBytes(salt);
		byte[] derived = derive(password.toCharArray(), salt, iterations, keyLengthBits);
		return "pbkdf2-sha512$" + iterations + "$"
			+ Base64.getEncoder().encodeToString(salt)
			+ "$" + Base64.getEncoder().encodeToString(derived);
	}

	public boolean verify(String storedHash, String password) {
		if (storedHash == null || password == null) {
			return false;
		}

		String[] parts = storedHash.split("\\$");
		if (parts.length != 4 || !"pbkdf2-sha512".equals(parts[0])) {
			return false;
		}

		int iterations;
		try {
			iterations = Integer.parseInt(parts[1]);
		} catch (NumberFormatException exception) {
			return false;
		}

		byte[] salt;
		byte[] expected;
		try {
			salt = Base64.getDecoder().decode(parts[2]);
			expected = Base64.getDecoder().decode(parts[3]);
		} catch (IllegalArgumentException exception) {
			return false;
		}

		byte[] derived = derive(password.toCharArray(), salt, iterations, expected.length * 8);
		return MessageDigest.isEqual(expected, derived);
	}

	private byte[] derive(char[] password, byte[] salt, int iterations, int keyLengthBits) {
		PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
		try {
			SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
			return factory.generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
			throw new IllegalStateException("Cannot derive PBKDF2 hash.", exception);
		} finally {
			spec.clearPassword();
			Arrays.fill(password, '\0');
		}
	}

	private static SecureRandom createSecureRandom() {
		try {
			return SecureRandom.getInstanceStrong();
		} catch (NoSuchAlgorithmException exception) {
			return new SecureRandom();
		}
	}
}
