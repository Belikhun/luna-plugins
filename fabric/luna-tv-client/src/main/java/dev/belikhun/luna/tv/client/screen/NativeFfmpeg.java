package dev.belikhun.luna.tv.client.screen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the ffmpeg the mod decodes H.264 with.
 *
 * One binary ships in the jar, for Windows x64, because that is the platform
 * where a player is least likely to already have ffmpeg and most likely to be
 * unwilling to install it. Everywhere else the mod asks PATH, and a machine
 * with no ffmpeg at all simply keeps taking the older stream of whole JPEGs -
 * which works, and only costs bandwidth.
 *
 * The binary is unpacked beside the game rather than run from inside the jar,
 * because a jar entry is not a file an operating system can execute. It is
 * named after a hash of its own contents, so a rebuilt decoder replaces itself
 * without any versioning and a half-written one can never be run: the unpack
 * writes to a temporary name and renames it into place.
 */
public final class NativeFfmpeg {

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	/** Points the mod at a decoder of your own, for testing or an odd platform. */
	private static final String OVERRIDE = "lunatv.ffmpeg";

	/** The one platform a decoder ships for. */
	private static final String BUNDLED = "windows-x86_64";

	private static boolean resolved;
	private static Path binary;
	private static String reason;

	private NativeFfmpeg() {
	}

	/**
	 * The decoder to spawn.
	 *
	 * Worked out once and remembered, because the answer involves hashing a few
	 * megabytes and asking the filesystem, and it cannot change while the game
	 * is running.
	 *
	 * @return the executable, or null when this machine has none
	 */
	public static synchronized Path binary() {
		if (!resolved) {
			resolved = true;
			binary = locate();
		}

		return binary;
	}

	/**
	 * Why there is no decoder, phrased for a log line.
	 *
	 * @return the reason, or null when there is one
	 */
	public static synchronized String reason() {
		binary();

		return reason;
	}

	private static Path locate() {
		String override = System.getProperty(OVERRIDE);

		if (override != null && !override.isBlank()) {
			Path chosen = Paths.get(override);

			if (Files.isExecutable(chosen)) {
				LOGGER.info("Luna TV decoder: {} (from -D{})", chosen, OVERRIDE);

				return chosen;
			}

			LOGGER.warn("Luna TV: -D{} points at {}, which is not executable", OVERRIDE, override);
		}

		Path unpacked = unpack();

		if (unpacked != null) {
			return unpacked;
		}

		Path onPath = search();

		if (onPath != null) {
			LOGGER.info("Luna TV decoder: {} (from PATH)", onPath);

			return onPath;
		}

		reason = "no bundled decoder for " + platform() + " and no ffmpeg on PATH";
		LOGGER.info("Luna TV: {}; screens will use the JPEG stream instead", reason);

		return null;
	}

	/** The platform key, matching the directory names the build script writes. */
	private static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		String cpu = switch (arch) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "aarch64";
			default -> arch;
		};

		if (os.contains("win")) {
			return "windows-" + cpu;
		}

		if (os.contains("mac") || os.contains("darwin")) {
			return "macos-" + cpu;
		}

		return "linux-" + cpu;
	}

	/**
	 * Unpacks the shipped decoder, if this platform is the one it was built for.
	 *
	 * @return the executable, or null when nothing ships for this platform
	 */
	private static Path unpack() {
		String platform = platform();

		if (!BUNDLED.equals(platform)) {
			return null;
		}

		String entry = "/lunatv/native/" + platform + "/ffmpeg.exe";

		try {
			String digest = hash(entry);

			if (digest == null) {
				return null;
			}

			Path home = Paths.get(System.getProperty("user.dir", "."), "luna-tv", "bin");

			Files.createDirectories(home);

			Path target = home.resolve("ffmpeg-" + digest + ".exe");

			if (Files.isExecutable(target)) {
				LOGGER.info("Luna TV decoder: {} (already unpacked)", target);

				return target;
			}

			Path staged = home.resolve("ffmpeg-" + digest + ".exe.part");

			try (InputStream from = NativeFfmpeg.class.getResourceAsStream(entry)) {
				if (from == null) {
					return null;
				}

				Files.copy(from, staged, StandardCopyOption.REPLACE_EXISTING);
			}

			staged.toFile().setExecutable(true, true);
			Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("Luna TV decoder: unpacked {}", target);

			return target;
		} catch (IOException failed) {
			reason = "could not unpack the decoder: " + failed;
			LOGGER.warn("Luna TV: {}", reason);

			return null;
		}
	}

	/**
	 * The content hash of a jar entry, which is what names the unpacked file.
	 *
	 * Streamed rather than read whole: it is several megabytes, and it is read
	 * once per game session at most.
	 */
	private static String hash(String entry) throws IOException {
		try (InputStream from = NativeFfmpeg.class.getResourceAsStream(entry)) {
			if (from == null) {
				return null;
			}

			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];

			while (true) {
				int read = from.read(buffer);

				if (read < 0) {
					break;
				}

				digest.update(buffer, 0, read);
			}

			return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IOException(impossible);
		}
	}

	/** Looks for an ffmpeg already installed on this machine. */
	private static Path search() {
		String path = System.getenv("PATH");

		if (path == null) {
			return null;
		}

		boolean windows = platform().startsWith("windows");
		String name = windows ? "ffmpeg.exe" : "ffmpeg";

		for (String directory : path.split(java.io.File.pathSeparator)) {
			if (directory.isBlank()) {
				continue;
			}

			Path candidate = Paths.get(directory, name);

			if (Files.isExecutable(candidate)) {
				return candidate;
			}
		}

		return null;
	}
}
