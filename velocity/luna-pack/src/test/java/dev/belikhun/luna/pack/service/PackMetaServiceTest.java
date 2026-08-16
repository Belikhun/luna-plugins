package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.pack.model.PackFormat;
import dev.belikhun.luna.pack.model.PackFormatRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackMetaServiceTest {
	@TempDir
	Path tempDir;

	private final PackMetaService service = new PackMetaService(
		LunaLogger.forLogger(Logger.getLogger("test"), false)
	);

	@Test
	void modernBoundsWin() throws IOException {
		Path zip = pack("{\"pack\":{\"pack_format\":64,\"supported_formats\":[1,64],\"min_format\":1,\"max_format\":75}}");

		PackFormatRange range = service.readRange(zip);

		assertEquals(new PackFormat(1, 0), range.min());
		assertEquals(new PackFormat(75, 0), range.max());
		assertEquals("min_format", range.source());
		assertFalse(range.clamped());
	}

	@Test
	void modernBoundsReadMajorMinorPairs() throws IOException {
		Path zip = pack("{\"pack\":{\"min_format\":[69,0],\"max_format\":[69,0]}}");

		PackFormatRange range = service.readRange(zip);

		assertEquals(new PackFormat(69, 0), range.min());
		assertEquals(new PackFormat(69, 0), range.max());
	}

	@Test
	void legacySupportedFormatsObjectIsClampedAtTheCeiling() throws IOException {
		Path zip = pack("{\"pack\":{\"pack_format\":15,\"supported_formats\":{\"min_inclusive\":15,\"max_inclusive\":99}}}");

		PackFormatRange range = service.readRange(zip);

		assertEquals(new PackFormat(15, 0), range.min());
		assertEquals(PackFormat.LEGACY_CEILING, range.max());
		assertEquals("supported_formats", range.source());
		assertTrue(range.clamped());
	}

	@Test
	void legacyPairAndSingleFormsParse() throws IOException {
		PackFormatRange pair = service.readRange(pack("{\"pack\":{\"pack_format\":34,\"supported_formats\":[34,64]}}"));
		PackFormatRange single = service.readRange(pack("{\"pack\":{\"pack_format\":34,\"supported_formats\":34}}"));

		assertEquals(new PackFormat(34, 0), pair.min());
		assertEquals(new PackFormat(64, 0), pair.max());
		assertFalse(pair.clamped());
		assertEquals(new PackFormat(34, 0), single.min());
		assertEquals(new PackFormat(34, 0), single.max());
	}

	@Test
	void packFormatAloneDeclaresOneFormat() throws IOException {
		PackFormatRange range = service.readRange(pack("{\"pack\":{\"pack_format\":15}}"));

		assertEquals(new PackFormat(15, 0), range.min());
		assertEquals(new PackFormat(15, 0), range.max());
		assertEquals("pack_format", range.source());
	}

	@Test
	void supportedFormatsObjectFallsBackToPackFormatForAMissingBound() throws IOException {
		PackFormatRange range = service.readRange(pack("{\"pack\":{\"pack_format\":15,\"supported_formats\":{\"min_inclusive\":9}}}"));

		assertEquals(new PackFormat(9, 0), range.min());
		assertEquals(new PackFormat(15, 0), range.max());
	}

	@Test
	void undeclaredOrBrokenPacksHaveNoRange() throws IOException {
		assertNull(service.readRange(pack("{\"pack\":{\"description\":\"no formats\"}}")));
		assertNull(service.readRange(pack("not json at all")));
		assertNull(service.readRange(packWithoutMcmeta()));
		assertNull(service.readRange(tempDir.resolve("missing.zip")));
	}

	private Path pack(String mcmeta) throws IOException {
		Path zip = Files.createTempFile(tempDir, "pack", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
			output.putNextEntry(new ZipEntry("pack.mcmeta"));
			output.write(mcmeta.getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
		return zip;
	}

	private Path packWithoutMcmeta() throws IOException {
		Path zip = Files.createTempFile(tempDir, "pack", ".zip");
		try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
			output.putNextEntry(new ZipEntry("assets/minecraft/lang/en_us.json"));
			output.write("{}".getBytes(StandardCharsets.UTF_8));
			output.closeEntry();
		}
		return zip;
	}
}
