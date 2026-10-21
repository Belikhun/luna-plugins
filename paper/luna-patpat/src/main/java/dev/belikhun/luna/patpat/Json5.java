package dev.belikhun.luna.patpat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;

/**
 * Reads the JSON5 that PatPat animation packs are written in: comments, trailing commas, unquoted
 * keys and single quotes. Comments and trailing commas are stripped first (outside strings), and
 * the rest is left to Gson's lenient reader.
 */
final class Json5 {
	private Json5() {
	}

	static JsonElement parse(String text) {
		JsonReader reader = new JsonReader(new StringReader(clean(text)));
		reader.setStrictness(Strictness.LENIENT);
		return JsonParser.parseReader(reader);
	}

	static String clean(String text) {
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		char quote = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (quote != 0) {
				out.append(c);
				if (c == '\\' && i + 1 < text.length()) {
					out.append(text.charAt(i + 1));
					i += 2;
					continue;
				}
				if (c == quote) {
					quote = 0;
				}
				i++;
				continue;
			}
			if (c == '"' || c == '\'') {
				quote = c;
				out.append(c);
				i++;
				continue;
			}
			if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
				while (i < text.length() && text.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
				int end = text.indexOf("*/", i + 2);
				i = end < 0 ? text.length() : end + 2;
				continue;
			}
			if (c == ',') {
				int j = i + 1;
				while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
					j++;
				}
				if (j < text.length() && (text.charAt(j) == '}' || text.charAt(j) == ']')) {
					i++;
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}
}
