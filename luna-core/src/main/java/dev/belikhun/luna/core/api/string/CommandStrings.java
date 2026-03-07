package dev.belikhun.luna.core.api.string;

import dev.belikhun.luna.core.api.ui.LunaPalette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CommandStrings {
	private CommandStrings() {
	}

	public static String usage(String root, Segment... segments) {
		return "<color:" + LunaPalette.AMBER_500 + ">ℹ Dùng: </color>" + syntax(root, segments);
	}

	public static String syntax(String root, Segment... segments) {
		StringBuilder builder = new StringBuilder();
		StringBuilder suggest = new StringBuilder();
		String normalizedRoot = normalize(root);
		builder.append(commandName(normalizedRoot));
		suggest.append(normalizedRoot);
		for (Segment segment : segments) {
			builder.append(" ").append(render(segment));
			suggest.append(" ").append(plainSegment(segment));
		}
		return clickable(builder.toString(), suggest.toString());
	}

	public static String arguments(Segment... segments) {
		StringBuilder builder = new StringBuilder();
		for (Segment segment : segments) {
			if (!builder.isEmpty()) {
				builder.append(" ");
			}
			builder.append(render(segment));
		}
		return builder.toString();
	}

	public static String syntaxRaw(String rawCommand) {
		return syntaxRaw(rawCommand, new Segment[0]);
	}

	public static String syntaxRaw(String rawCommand, Segment... segments) {
		if (rawCommand == null || rawCommand.isBlank()) {
			return "";
		}

		String[] tokens = rawCommand.trim().split("\\s+");
		if (tokens.length == 0) {
			return "";
		}

		StringBuilder builder = new StringBuilder(commandName(tokens[0]));
		StringBuilder suggest = new StringBuilder(tokens[0]);
		for (int i = 1; i < tokens.length; i++) {
			builder.append(" ").append(renderRawToken(tokens[i]));
			suggest.append(" ").append(tokens[i]);
		}
		for (Segment segment : segments) {
			builder.append(" ").append(render(segment));
			suggest.append(" ").append(plainSegment(segment));
		}
		return clickable(builder.toString(), suggest.toString());
	}

	public static Segment literal(String text) {
		return new Segment(SegmentKind.LITERAL, normalize(text), "");
	}

	public static Segment required(String name, String type) {
		return new Segment(SegmentKind.REQUIRED, normalize(name), normalizeType(type));
	}

	public static Segment optional(String name, String type) {
		return new Segment(SegmentKind.OPTIONAL, normalize(name), normalizeType(type));
	}

	public static Segment requiredChoice(String name, String... values) {
		return required(name, joinChoices(values));
	}

	public static Segment optionalChoice(String name, String... values) {
		return optional(name, joinChoices(values));
	}

	private static String commandName(String text) {
		return "<color:" + LunaPalette.VIOLET_500 + ">" + normalize(text) + "</color>";
	}

	private static String render(Segment segment) {
		return switch (segment.kind()) {
			case LITERAL -> "<color:" + LunaPalette.TEAL_500 + ">" + segment.name() + "</color>";
			case REQUIRED -> "<color:" + typeColor(segment.type()) + ">\\<</color>" + renderParamName(segment.name(), segment.type()) + "<color:" + typeColor(segment.type()) + ">></color>";
			case OPTIONAL -> "<color:" + typeColor(segment.type()) + ">[</color>" + renderParamName(segment.name(), segment.type()) + "<color:" + typeColor(segment.type()) + ">]</color>";
		};
	}

	private static String renderParamName(String name, String type) {
		return "<color:" + typeColor(type) + ">" + normalize(name) + "</color>";
	}

	private static String typeColor(String type) {
		String normalized = normalizeType(type).toLowerCase();
		String color = LunaPalette.NEUTRAL_300;
		if (normalized.contains("number") || normalized.contains("int") || normalized.contains("double") || normalized.contains("float") || normalized.contains("long")) {
			color = LunaPalette.GOLD_500;
		} else if (normalized.contains("bool")) {
			color = LunaPalette.LIME_500;
		} else if (normalized.contains("mini") || normalized.contains("json") || normalized.contains("component")) {
			color = LunaPalette.VIOLET_500;
		} else if (normalized.contains("|") || normalized.contains("enum") || normalized.contains("choice")) {
			color = LunaPalette.PINK_500;
		}
		return color;
	}

	private static String plainSegment(Segment segment) {
		return switch (segment.kind()) {
			case LITERAL -> segment.name();
			case REQUIRED -> segment.name();
			case OPTIONAL -> segment.name();
		};
	}

	private static String clickable(String visible, String suggestCommand) {
		String command = normalize(suggestCommand);
		if (command.isBlank()) {
			return visible;
		}

		String escapedCommand = command.replace("\\", "\\\\").replace("'", "\\'");
		String hover = "<color:" + LunaPalette.SKY_500 + ">Nhấn để chèn lệnh vào ô chat</color>";
		return "<click:suggest_command:'" + escapedCommand + "'><hover:show_text:'" + hover + "'>" + visible + "</hover></click>";
	}

	private static String renderRawToken(String token) {
		String value = normalize(token);
		if (value.startsWith("<") && value.endsWith(">") && value.length() > 2) {
			String body = value.substring(1, value.length() - 1);
			String[] parts = body.split(":", 2);
			if (parts.length == 2) {
				return render(required(parts[0], parts[1]));
			}
			if (body.contains("|")) {
				return render(required("giá_trị", body));
			}
			return render(required(body, "text"));
		}

		if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
			String body = value.substring(1, value.length() - 1);
			String[] parts = body.split(":", 2);
			if (parts.length == 2) {
				return render(optional(parts[0], parts[1]));
			}
			if (body.contains("|")) {
				return render(optional("giá_trị", body));
			}
			return render(optional(body, "text"));
		}

		return render(literal(value));
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	private static String normalizeType(String type) {
		String normalized = normalize(type);
		return normalized.isBlank() ? "text" : normalized;
	}

	private static String joinChoices(String... values) {
		List<String> normalized = new ArrayList<>();
		for (String value : Arrays.asList(values)) {
			String text = normalize(value);
			if (!text.isBlank()) {
				normalized.add(text);
			}
		}
		if (normalized.isEmpty()) {
			return "text";
		}
		return String.join("|", normalized);
	}

	public enum SegmentKind {
		LITERAL,
		REQUIRED,
		OPTIONAL
	}

	public record Segment(SegmentKind kind, String name, String type) {
	}
}
