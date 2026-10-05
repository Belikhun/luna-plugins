package dev.belikhun.luna.core.api.placeholder;

/**
 * Computes one placeholder's value.
 *
 * A resolver reads the samples and parameters on the request and returns
 * MiniMessage-flavoured text. It returns null to say "no value for this
 * request", which the caller renders as the placeholder's own text rather than
 * as an empty string. It runs on whatever thread asked, so it must not touch
 * game state directly; the samples exist so it does not have to.
 *
 * @param <P> the platform's player type
 */
@FunctionalInterface
public interface PlaceholderResolver<P> {
	String resolve(PlaceholderRequest<P> request);
}
