package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.messenger.ProxyMessageTemplateRenderer;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRef;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSyntax;
import dev.belikhun.luna.core.api.placeholder.PlaceholderTemplate;
import dev.belikhun.luna.core.api.placeholder.Resolution;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The one way a proxy template becomes text.
 *
 * A template is parsed once and every placeholder in it resolved in a single
 * pass through the layers the caller hands over: values the caller computed,
 * values a backend exported with the message, then the proxy's own engine. A
 * template still saying {@code %luna_…%} for a proxy fact resolves through the
 * {@code lunav} namespace, so old formats keep working while they are migrated.
 * A value is never scanned again for placeholders. What no layer answers stays
 * as written, so a typo shows instead of vanishing.
 *
 * After that pass the text goes once through MiniMessage with MiniPlaceholders'
 * resolvers, so tags other plugins register keep resolving too.
 */
public final class VelocityTemplateRenderer implements ProxyMessageTemplateRenderer {
	private static final MiniMessage MINI = MiniMessage.miniMessage();
	private static final Set<PlaceholderSyntax> SYNTAXES = EnumSet.of(PlaceholderSyntax.PERCENT, PlaceholderSyntax.MINI_TAG);
	private static final Map<String, String> NAMESPACE_ALIASES = Map.of("luna", LunavPlaceholders.NAMESPACE);

	private final PlaceholderRuntime<Player> runtime;
	private final LunaLogger logger;
	private final boolean miniPlaceholdersPresent;
	private volatile boolean parseFailureReported;

	public VelocityTemplateRenderer(PlaceholderRuntime<Player> runtime, boolean miniPlaceholdersPresent, LunaLogger logger) {
		this.runtime = runtime;
		this.miniPlaceholdersPresent = miniPlaceholdersPresent;
		this.logger = logger.scope("Templates");
	}

	@Override
	public String renderTemplate(String template, Map<String, String> values) {
		return render(template, null, values);
	}

	/**
	 * Renders a template for a viewer.
	 *
	 * @param template the template text
	 * @param player   the viewer, or null for a viewer-less render
	 * @param layers   value maps consulted in order before the engine; a key is
	 *                 either the bare identifier ({@code message}) or the full one
	 * @return the rendered MiniMessage source
	 */
	@SafeVarargs
	public final String render(String template, Player player, Map<String, String>... layers) {
		if (template == null || template.isEmpty()) {
			return "";
		}

		PlaceholderTemplate parsed = PlaceholderTemplate.cached(template, SYNTAXES);
		String rendered = parsed.render(ref -> resolve(ref, player, layers));
		return miniMessagePass(rendered, player);
	}

	/** Resolves one ref through the layers, then the engine, then the namespace aliases. */
	public String resolve(PlaceholderRef ref, Player player, Map<String, String>[] layers) {
		String inner = ref.source().length() >= 2 && ref.syntax() == PlaceholderSyntax.PERCENT
			? ref.source().substring(1, ref.source().length() - 1)
			: ref.identifier();

		if (layers != null) {
			for (Map<String, String> layer : layers) {
				if (layer == null || layer.isEmpty()) {
					continue;
				}

				String value = layer.get(inner);

				if (value == null) {
					value = layer.get(ref.identifier());
				}

				if (value != null) {
					return value;
				}
			}
		}

		Resolution<Player> resolution = runtime.engine().resolve(ref, player);

		if (resolution.hasValue()) {
			return resolution.value();
		}

		String alias = NAMESPACE_ALIASES.get(ref.namespace());

		if (alias != null && !runtime.registry().claims(ref.namespace())) {
			PlaceholderRef aliased = new PlaceholderRef(alias, ref.tokens(), ref.args(), ref.safe(), ref.syntax(), ref.source());
			Resolution<Player> aliasedResolution = runtime.engine().resolve(aliased, player);

			if (aliasedResolution.hasValue()) {
				return aliasedResolution.value();
			}
		}

		return null;
	}

	/** Every placeholder a template names, for callers that check a config up front. */
	public Set<PlaceholderRef> refs(String template) {
		return PlaceholderTemplate.cached(template == null ? "" : template, SYNTAXES).refs();
	}

	private String miniMessagePass(String text, Player player) {
		if (!miniPlaceholdersPresent || text.indexOf('<') < 0) {
			return text;
		}

		try {
			TagResolver global = MiniPlaceholders.globalPlaceholders();
			TagResolver audience = MiniPlaceholders.audiencePlaceholders();
			Component component = player == null
				? MINI.deserialize(text, global, audience)
				: MINI.deserialize(text, player, global, audience);
			return MINI.serialize(component);
		} catch (NoClassDefFoundError | RuntimeException failure) {
			if (!parseFailureReported) {
				parseFailureReported = true;
				logger.warn("Không phân giải được MiniMessage cho template: " + text + " (" + failure + ")");
			}

			return text;
		}
	}
}
