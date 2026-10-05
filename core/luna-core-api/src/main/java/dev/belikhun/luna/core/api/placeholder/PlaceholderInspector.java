package dev.belikhun.luna.core.api.placeholder;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text reports about placeholders, shared by every platform's debug command
 * and HTTP route so they all say the same thing the same way.
 *
 * @param <P> the platform's player type
 */
public final class PlaceholderInspector<P> {
	private final PlaceholderRuntime<P> runtime;

	public PlaceholderInspector(PlaceholderRuntime<P> runtime) {
		this.runtime = runtime;
	}

	/**
	 * Explains how text is tokenised and which spec each token would reach,
	 * without resolving anything.
	 */
	public List<String> parse(String text) {
		List<String> lines = new ArrayList<>();
		List<PlaceholderGrammar.Segment> segments = PlaceholderGrammar.scan(text, EnumSet.allOf(PlaceholderSyntax.class), tagFilter());
		int refs = 0;

		for (PlaceholderGrammar.Segment segment : segments) {
			if (segment.isRef()) {
				refs++;
			}
		}

		lines.add("── parse · " + refs + " placeholder(s) in " + text.length() + " chars ──");

		if (refs == 0 && !text.isBlank() && !text.contains("%") && !text.contains("{") && !text.contains("<")) {
			lines.addAll(describe(PlaceholderGrammar.parse(text)));
			return lines;
		}

		for (PlaceholderGrammar.Segment segment : segments) {
			if (!segment.isRef()) {
				continue;
			}

			lines.addAll(describe(segment.ref()));
		}

		return lines;
	}

	/**
	 * Renders text for a player and shows each placeholder's value, spec and
	 * timing.
	 */
	public List<String> resolve(String text, P player, String playerLabel) {
		List<String> lines = new ArrayList<>();
		PlaceholderTemplate template = PlaceholderTemplate.parse(text, EnumSet.allOf(PlaceholderSyntax.class));
		List<PlaceholderGrammar.Segment> segments = PlaceholderGrammar.scan(text, EnumSet.allOf(PlaceholderSyntax.class), tagFilter());
		ServerSample server = runtime.engine().server();
		PlayerSample viewer = player == null ? null : runtime.engine().viewer(player);
		String sampleAge = server.ageMillis() < 0 ? "no server sample yet" : "server sample " + server.ageMillis() + "ms old";
		String viewerAge = player == null
			? "no player"
			: viewer == null ? "player has no sample" : "player sample " + viewer.ageMillis() + "ms old";

		boolean bare = segments.stream().noneMatch(PlaceholderGrammar.Segment::isRef) && !text.isBlank();
		List<PlaceholderRef> refs = new ArrayList<>();

		if (bare) {
			refs.add(PlaceholderGrammar.parse(text));
		} else {
			for (PlaceholderGrammar.Segment segment : segments) {
				if (segment.isRef()) {
					refs.add(segment.ref());
				}
			}
		}

		lines.add("── resolve for " + playerLabel + " · " + sampleAge + " · " + viewerAge + " ──");

		if (!bare) {
			String rendered = template.render(ref -> {
				Resolution<P> resolution = runtime.engine().resolve(ref, player);
				return resolution.hasValue() ? resolution.value() : null;
			});
			lines.add("  = " + rendered);
		}

		for (PlaceholderRef ref : refs) {
			Resolution<P> resolution = runtime.engine().resolve(ref, player);
			String spec = resolution.spec() == null ? "-" : resolution.spec().canonical();
			String meta = resolution.spec() == null
				? ""
				: " " + resolution.spec().scope().name().toLowerCase(Locale.ROOT) + "/" + resolution.spec().refresh().name().toLowerCase(Locale.ROOT);
			String value = resolution.hasValue() ? "\"" + resolution.value() + "\"" : "(no value)";
			String error = resolution.error() == null ? "" : " error=" + resolution.error();

			lines.add(String.format(Locale.ROOT, "  %s = %s   [%s · %s%s · %dµs%s]",
				ref.source(), value, resolution.status().name().toLowerCase(Locale.ROOT), spec, meta, resolution.nanos() / 1000L, error));
		}

		return lines;
	}

	/** Lists every registered spec, optionally in one namespace. */
	public List<String> list(String namespaceFilter) {
		List<String> lines = new ArrayList<>();
		Map<String, List<PlaceholderRegistry.Entry<P>>> entries = runtime.registry().entries();
		int total = 0;

		for (Map.Entry<String, List<PlaceholderRegistry.Entry<P>>> namespace : entries.entrySet()) {
			if (namespaceFilter != null && !namespaceFilter.isBlank() && !namespace.getKey().equalsIgnoreCase(namespaceFilter.trim())) {
				continue;
			}

			lines.add("[" + namespace.getKey() + "] " + namespace.getValue().size() + " spec(s)");

			for (PlaceholderRegistry.Entry<P> entry : namespace.getValue()) {
				PlaceholderSpec<P> spec = entry.spec();
				total++;
				lines.add(String.format(Locale.ROOT, "  %%%s%%  %s/%s  by %s  %s",
					spec.canonical(),
					spec.scope().name().toLowerCase(Locale.ROOT),
					spec.refresh().name().toLowerCase(Locale.ROOT),
					owner(entry.owner()),
					spec.description()));
			}
		}

		lines.add(0, "── " + total + " placeholder spec(s) in " + entries.size() + " namespace(s) · " + runtime.engine().sampledPlayers() + " sampled player(s) ──");
		return lines;
	}

	private List<String> describe(PlaceholderRef ref) {
		List<String> lines = new ArrayList<>();
		String match = runtime.registry().match(ref)
			.map(found -> found.spec().canonical() + " " + found.params())
			.orElse(runtime.registry().claims(ref.namespace()) ? "no spec in namespace" : "namespace not claimed (foreign?)");

		lines.add(String.format(Locale.ROOT, "  %s → ns=%s tokens=%s args=%s safe=%s syntax=%s → %s",
			ref.source(), ref.namespace(), ref.tokens(), ref.args(), ref.safe(), ref.syntax().name().toLowerCase(Locale.ROOT), match));
		return lines;
	}

	private java.util.function.Predicate<PlaceholderRef> tagFilter() {
		return ref -> PlaceholderGrammar.DEFAULT_TAG_FILTER.test(ref) && runtime.registry().claims(ref.namespace());
	}

	private static String owner(Object owner) {
		if (owner instanceof String text) {
			return text;
		}

		return owner == null ? "?" : owner.getClass().getSimpleName();
	}
}
