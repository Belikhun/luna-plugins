package dev.belikhun.luna.core.api.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class LunaLore {
	private static final int DEFAULT_MAX_WORDS = 7;

	private LunaLore() {
	}

	public static List<String> wrapLoreLine(String line) {
		return wrapLoreLine(line, DEFAULT_MAX_WORDS);
	}

	public static List<String> wrapLoreLine(String line, int maxWordsPerLine) {
		if (line == null || line.isEmpty()) {
			return List.of("");
		}

		int maxWords = Math.max(1, maxWordsPerLine);
		String normalized = normalizeLoreColor(line);
		int leadingSpaces = leadingSpaceCount(normalized);
		String continuationIndent = " ".repeat(leadingSpaces + 1);
		List<String> tokens = tokenizeMiniMessage(normalized);
		if (tokens.isEmpty()) {
			return List.of(normalized);
		}

		ArrayList<String> wrapped = new ArrayList<>();
		ArrayList<OpenTag> activeTags = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		if (leadingSpaces > 0) {
			current.append(" ".repeat(leadingSpaces));
		}
		int words = 0;
		boolean hasVisibleText = false;
		boolean pendingSpace = false;

		for (String token : tokens) {
			if (isMiniTag(token)) {
				current.append(token);
				trackTagState(token, activeTags);
				continue;
			}

			if (token.isBlank()) {
				if (hasVisibleText) {
					pendingSpace = true;
				}
				continue;
			}

			int tokenWords = visibleWordCount(token);
			if (words > 0 && tokenWords > 0 && words + tokenWords > maxWords) {
				wrapped.add(closeActiveTags(trimTrailingSpaces(current.toString()), activeTags));
				current = new StringBuilder(reopenActiveTags(activeTags));
				current.append(continuationIndent);
				words = 0;
				hasVisibleText = false;
				pendingSpace = false;
			}

			if (hasVisibleText && pendingSpace) {
				current.append(' ');
			}
			current.append(token);
			hasVisibleText = true;
			pendingSpace = false;
			words += tokenWords;
		}

		String tail = trimTrailingSpaces(current.toString());
		if (!tail.isEmpty()) {
			wrapped.add(closeActiveTags(tail, activeTags));
		}

		return wrapped.isEmpty() ? List.of(normalized) : wrapped;
	}

	public static String normalizeLoreColor(String line) {
		return line
			.replace("<gray>", "<white>")
			.replace("</gray>", "</white>")
			.replace("<dark_gray>", "<white>")
			.replace("</dark_gray>", "</white>");
	}

	private static int leadingSpaceCount(String text) {
		int count = 0;
		while (count < text.length() && text.charAt(count) == ' ') {
			count++;
		}
		return count;
	}

	private static List<String> tokenizeMiniMessage(String text) {
		ArrayList<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '<') {
				if (!current.isEmpty()) {
					tokens.add(current.toString());
					current = new StringBuilder();
				}
				int end = text.indexOf('>', i);
				if (end < 0) {
					current.append(text.substring(i));
					break;
				}
				tokens.add(text.substring(i, end + 1));
				i = end;
				continue;
			}

			if (Character.isWhitespace(ch)) {
				if (!current.isEmpty()) {
					tokens.add(current.toString());
					current = new StringBuilder();
				}
				tokens.add(" ");
				continue;
			}

			current.append(ch);
		}

		if (!current.isEmpty()) {
			tokens.add(current.toString());
		}

		return tokens;
	}

	private static boolean isMiniTag(String token) {
		return token.length() >= 3 && token.charAt(0) == '<' && token.charAt(token.length() - 1) == '>';
	}

	private static void trackTagState(String token, List<OpenTag> activeTags) {
		String tag = token.substring(1, token.length() - 1).trim();
		if (tag.isEmpty()) {
			return;
		}

		if (tag.equalsIgnoreCase("reset")) {
			activeTags.clear();
			return;
		}

		if (tag.startsWith("/")) {
			String closingName = tag.substring(1).trim().toLowerCase(Locale.ROOT);
			for (int i = activeTags.size() - 1; i >= 0; i--) {
				if (activeTags.get(i).name().equals(closingName)) {
					activeTags.remove(i);
					break;
				}
			}
			return;
		}

		if (tag.endsWith("/")) {
			return;
		}

		String name = tag.split("[:\\s]", 2)[0].toLowerCase(Locale.ROOT);
		if (name.equals("newline") || name.equals("br")) {
			return;
		}

		activeTags.add(new OpenTag(name, token));
	}

	private static String closeActiveTags(String line, List<OpenTag> activeTags) {
		StringBuilder builder = new StringBuilder(line);
		for (int i = activeTags.size() - 1; i >= 0; i--) {
			builder.append("</").append(activeTags.get(i).name()).append('>');
		}
		return builder.toString();
	}

	private static String reopenActiveTags(List<OpenTag> activeTags) {
		StringBuilder builder = new StringBuilder();
		for (OpenTag activeTag : activeTags) {
			builder.append(activeTag.openTag());
		}
		return builder.toString();
	}

	private static String trimTrailingSpaces(String value) {
		int end = value.length();
		while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end);
	}

	private static int visibleWordCount(String token) {
		String plain = token.replaceAll("<[^>]+>", "").trim();
		if (plain.isEmpty()) {
			return 0;
		}

		return (int) Arrays.stream(plain.split("[_\\p{Punct}\\s]+"))
			.filter(part -> !part.isBlank())
			.filter(LunaLore::containsLetterOrDigit)
			.filter(part -> part.codePointCount(0, part.length()) > 3)
			.count();
	}

	private static boolean containsLetterOrDigit(String value) {
		for (int i = 0; i < value.length();) {
			int codePoint = value.codePointAt(i);
			if (Character.isLetterOrDigit(codePoint)) {
				return true;
			}
			i += Character.charCount(codePoint);
		}

		return false;
	}

	private record OpenTag(String name, String openTag) {
	}
}
