package dev.belikhun.luna.deathswap.world;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.stream.Stream;

/** Plain file operations on world folders, none of which touch Bukkit. */
public final class WorldFiles {
	private WorldFiles() {
	}

	/** Delete a folder and everything below it. Returns false when something stayed behind. */
	public static boolean deleteTree(Path root) {
		if (root == null || !Files.exists(root)) {
			return true;
		}

		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// checked below
				}
			});
		} catch (IOException ignored) {
			// checked below
		}
		return !Files.exists(root);
	}

	/** Move a folder, falling back to copy-and-delete across file systems. */
	public static void move(Path from, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			copyTree(from, to);
			deleteTree(from);
		}
	}

	public static void copyTree(Path from, Path to) throws IOException {
		Files.walkFileTree(from, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(to.resolve(from.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** Move every entry of one folder into another, replacing what is there. */
	public static void mergeInto(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}
		Files.createDirectories(to);
		try (Stream<Path> children = Files.list(from)) {
			for (Path child : children.toList()) {
				Path target = to.resolve(child.getFileName().toString());
				if (Files.isDirectory(child) && Files.isDirectory(target)) {
					mergeInto(child, target);
					deleteTree(child);
				} else {
					deleteTree(target);
					move(child, target);
				}
			}
		}
	}

	public static long sizeOf(Path root) {
		if (root == null || !Files.exists(root)) {
			return 0L;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (IOException exception) {
					return 0L;
				}
			}).sum();
		} catch (IOException exception) {
			return 0L;
		}
	}
}
