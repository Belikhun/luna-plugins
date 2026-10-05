package dev.belikhun.luna.core.velocity.placeholder;

import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import dev.belikhun.luna.core.api.placeholder.PlaceholderGrammar;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRef;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRegistry;
import dev.belikhun.luna.core.api.placeholder.PlaceholderRuntime;
import dev.belikhun.luna.core.api.placeholder.PlaceholderScope;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSpec;
import dev.belikhun.luna.core.api.placeholder.PlaceholderSyntax;
import dev.belikhun.luna.core.api.placeholder.Resolution;
import dev.belikhun.luna.core.velocity.BuildConstants;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One MiniPlaceholders expansion per registry namespace, every tag delegating
 * to the engine.
 *
 * A tag name is a spec's literal prefix; whatever the pattern takes as
 * parameters rides after the colon, {@code <lunav_server_tps:survival>}, so the
 * expansion never has to enumerate backends or glyph keys. An alias namespace
 * ({@code luna} standing for {@code lunav} while old templates are migrated)
 * is registered as a second expansion whose tags rewrite the namespace.
 */
public final class VelocityMiniPlaceholdersAdapter {
	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

	private final PlaceholderRuntime<Player> runtime;
	private final LunaLogger logger;
	private final Map<String, String> aliasNamespaces = new HashMap<>();
	private final List<Expansion> expansions = new ArrayList<>();

	public VelocityMiniPlaceholdersAdapter(PlaceholderRuntime<Player> runtime, LunaLogger logger) {
		this.runtime = runtime;
		this.logger = logger.scope("MiniPlaceholders");
	}

	/** Registers {@code alias} as a second name for {@code target} until the next sync. */
	public void alias(String alias, String target) {
		aliasNamespaces.put(alias, target);
	}

	/** Rebuilds every expansion from the registry. */
	public synchronized void sync() {
		unregisterAll();

		for (Map.Entry<String, List<PlaceholderRegistry.Entry<Player>>> namespace : runtime.registry().entries().entrySet()) {
			register(namespace.getKey(), namespace.getKey(), namespace.getValue());
		}

		for (Map.Entry<String, String> alias : aliasNamespaces.entrySet()) {
			List<PlaceholderRegistry.Entry<Player>> target = runtime.registry().entries().get(alias.getValue());

			if (target != null && !runtime.registry().claims(alias.getKey())) {
				register(alias.getKey(), alias.getValue(), target);
			}
		}
	}

	/** Withdraws every expansion. */
	public synchronized void unregisterAll() {
		for (Expansion expansion : expansions) {
			try {
				if (expansion.registered()) {
					expansion.unregister();
				}
			} catch (Throwable throwable) {
				logger.debug("Không thể gỡ MiniPlaceholders expansion: " + throwable);
			}
		}

		expansions.clear();
	}

	private void register(String tagNamespace, String resolveNamespace, List<PlaceholderRegistry.Entry<Player>> entries) {
		Expansion.Builder builder = Expansion.builder(tagNamespace)
			.author("Belikhun")
			.version(BuildConstants.VERSION);
		Set<String> taken = new HashSet<>();
		int count = 0;

		for (PlaceholderRegistry.Entry<Player> entry : entries) {
			PlaceholderSpec<Player> spec = entry.spec();
			String tagName = spec.key().literalPrefix();

			if (tagName.isEmpty()) {
				// a pattern that starts with a parameter cannot be a tag name; it is
				// reachable as <namespace:arg> through the empty-name fallback below
				tagName = "";
			}

			if (!taken.add(tagName)) {
				continue;
			}

			String tokens = tagName;

			if (spec.scope() == PlaceholderScope.SERVER) {
				builder.globalPlaceholder(tagName.isEmpty() ? resolveNamespace : tagName, (queue, context) -> resolve(resolveNamespace, tokens, queue, null));
			} else {
				builder.audiencePlaceholder(Player.class, tagName.isEmpty() ? resolveNamespace : tagName, (player, queue, context) -> resolve(resolveNamespace, tokens, queue, player));
			}

			count++;
		}

		try {
			Expansion expansion = builder.build();
			expansion.register();
			expansions.add(expansion);
			logger.success("Đã đăng ký MiniPlaceholders namespace <" + tagNamespace + "_…> với " + count + " tag.");
		} catch (Throwable throwable) {
			logger.error("Không thể đăng ký MiniPlaceholders namespace " + tagNamespace + ".", throwable);
		}
	}

	private Tag resolve(String namespace, String tokens, ArgumentQueue queue, Player player) {
		List<String> args = new ArrayList<>();

		while (queue.hasNext()) {
			args.add(queue.pop().value());
		}

		String identifier = tokens.isEmpty() ? namespace : namespace + "_" + tokens;
		PlaceholderRef ref = PlaceholderGrammar.parse(identifier).withArgs(args);
		Resolution<Player> resolution = runtime.engine().resolve(ref, player);

		if (!resolution.hasValue()) {
			return Tag.inserting(Component.text(PlaceholderGrammar.format(ref, PlaceholderSyntax.PERCENT)));
		}

		return textTag(resolution.value());
	}

	/**
	 * Wrap a placeholder's value as a tag the surrounding message can survive.
	 *
	 * The empty parent is load-bearing. MiniMessage writes an inserted component's
	 * style as an unclosed tag when the component carries that style at its root,
	 * so on a serialize/re-parse round trip the rest of the line becomes a child
	 * of it and inherits the colour. A styleless parent gives the serializer a
	 * boundary to close at.
	 */
	private static Tag textTag(String value) {
		if (value == null || value.isEmpty()) {
			return Tag.inserting(Component.empty());
		}

		return Tag.inserting(Component.empty().append(MINI_MESSAGE.deserialize(value)));
	}
}
