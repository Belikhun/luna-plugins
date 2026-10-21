package dev.belikhun.luna.patpat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Fetches a PatPat animation pack from Modrinth: the project's newest version, its primary zip.
 * Accepts a project slug, id, or a modrinth.com URL.
 */
final class ModrinthImporter {
	private static final String API = "https://api.modrinth.com/v2/";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final String userAgent;

	ModrinthImporter(String userAgent) {
		this.userAgent = userAgent;
	}

	record Download(byte[] zip, AnimationLibrary.Source source, String version) {
	}

	static String slugOf(String input) {
		String s = input.trim();
		int at = s.indexOf("modrinth.com/");
		if (at >= 0) {
			String[] parts = s.substring(at + "modrinth.com/".length()).split("[/?#]");
			if (parts.length >= 2) {
				return parts[1];
			}
		}
		return s;
	}

	Download fetch(String input) throws IOException, InterruptedException {
		String slug = slugOf(input);
		String enc = URLEncoder.encode(slug, StandardCharsets.UTF_8);
		JsonObject project = get(API + "project/" + enc).getAsJsonObject();
		JsonArray versions = get(API + "project/" + enc + "/version").getAsJsonArray();
		if (versions.isEmpty()) {
			throw new IOException("dự án không có phiên bản nào để tải");
		}
		// versions come newest first; take the first with a zip file
		for (JsonElement v : versions) {
			JsonObject version = v.getAsJsonObject();
			JsonObject file = null;
			for (JsonElement f : version.getAsJsonArray("files")) {
				JsonObject fo = f.getAsJsonObject();
				if (!fo.get("filename").getAsString().toLowerCase().endsWith(".zip")) continue;
				if (file == null || fo.get("primary").getAsBoolean()) file = fo;
			}
			if (file == null) {
				continue;
			}
			byte[] zip = download(file.get("url").getAsString());
			String license = project.has("license") && project.get("license").isJsonObject()
				? project.getAsJsonObject("license").get("id").getAsString() : null;
			String author = version.has("author_id") ? author(version.get("author_id").getAsString()) : null;
			AnimationLibrary.Source source = new AnimationLibrary.Source(
				AnimationLibrary.slug(project.get("slug").getAsString()),
				project.get("title").getAsString(),
				"https://modrinth.com/resourcepack/" + project.get("slug").getAsString(),
				license,
				author);
			return new Download(zip, source, version.get("version_number").getAsString());
		}
		throw new IOException("không có phiên bản nào kèm tệp .zip");
	}

	private String author(String userId) {
		try {
			return get(API + "user/" + URLEncoder.encode(userId, StandardCharsets.UTF_8)).getAsJsonObject().get("username").getAsString();
		} catch (Exception e) {
			return null;
		}
	}

	private JsonElement get(String url) throws IOException, InterruptedException {
		HttpResponse<String> r = http.send(request(url), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() == 404) {
			throw new IOException("không tìm thấy trên Modrinth");
		}
		if (r.statusCode() / 100 != 2) {
			throw new IOException("Modrinth trả về " + r.statusCode());
		}
		return JsonParser.parseString(r.body());
	}

	private byte[] download(String url) throws IOException, InterruptedException {
		HttpResponse<byte[]> r = http.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() / 100 != 2) {
			throw new IOException("tải tệp thất bại: " + r.statusCode());
		}
		if (r.body().length > 32 * 1024 * 1024) {
			throw new IOException("tệp quá lớn");
		}
		return r.body();
	}

	private HttpRequest request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", userAgent).GET().build();
	}

	static List<String> describe(Download d) {
		return List.of(d.source().title(), d.version());
	}
}
