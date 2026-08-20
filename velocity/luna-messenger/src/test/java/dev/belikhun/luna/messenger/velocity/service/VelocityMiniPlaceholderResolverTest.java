package dev.belikhun.luna.messenger.velocity.service;

import dev.belikhun.luna.core.api.logging.LunaLogger;
import io.github.miniplaceholders.api.Expansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the percent-to-MiniMessage rewriting the proxy formats depend on.
 *
 * <p>Two rules here are invisible at compile time and both shipped broken once: a
 * MiniPlaceholders placeholder is addressed as {@code <namespace_name>} rather than
 * {@code <namespace:name>}, and MiniMessage refuses a tag name outside {@code [a-zA-Z0-9_-]},
 * so a glyph suffix such as the status dot has to travel as an argument.
 */
class VelocityMiniPlaceholderResolverTest {
	private static final String NO_GLYPH = "none";

	@BeforeAll
	static void registerExpansion() {
		Expansion.builder("luna")
			.author("Belikhun")
			.version("test")
			.globalPlaceholder("player_status", (queue, context) -> {
				String glyph = queue.hasNext() ? queue.pop().value() : NO_GLYPH;
				return Tag.inserting(Component.text("dot:" + glyph));
			})
			.build()
			.register();
	}

	private VelocityMiniPlaceholderResolver resolver() {
		return new VelocityMiniPlaceholderResolver(LunaLogger.forLogger(Logger.getLogger("test"), false));
	}

	@Test
	void resolvesAGlyphSuffixAsAnArgument() {
		String rendered = resolver().resolve(null, "%luna_player_status_⏺% tail", Set.of());
		assertEquals("dot:⏺ tail", rendered);
	}

	@Test
	void resolvesAnyGlyphSuffixNotJustTheDot() {
		String rendered = resolver().resolve(null, "%luna_player_status_★% tail", Set.of());
		assertEquals("dot:★ tail", rendered);
	}

	@Test
	void resolvesTheSuffixlessForm() {
		String rendered = resolver().resolve(null, "%luna_player_status% tail", Set.of());
		assertEquals("dot:" + NO_GLYPH + " tail", rendered);
	}

	@Test
	void leavesPreservedTokensForTheBackendValuePass() {
		String rendered = resolver().resolve(null, "%luckperms_prefix% tail", Set.of("luckperms_prefix"));
		assertEquals("%luckperms_prefix% tail", rendered);
	}

	@Test
	void keepsThePercentFormWhenOnlyANamespaceWouldRemain() {
		String rendered = resolver().resolve(null, "%luna_⏺% tail", Set.of());
		assertEquals("%luna_⏺% tail", rendered);
	}

	@Test
	void stillAddressesAnUnknownButTagSafeName() {
		// Unresolved is fine; what matters is that the tag form is the one MiniPlaceholders reads.
		String rendered = resolver().resolve(null, "%unknown_thing% tail", Set.of());
		assertEquals("\\<unknown_thing> tail", rendered);
	}
}
