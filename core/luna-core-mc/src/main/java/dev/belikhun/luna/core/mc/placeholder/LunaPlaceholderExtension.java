package dev.belikhun.luna.core.mc.placeholder;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;

/**
 * Placeholders published by a module other than the core.
 *
 * On Paper each module registers its own PlaceholderAPI expansion and the
 * namespace comes with it; the mod loaders have no such registry, so the core
 * keeps the one resolver and a module hands it this instead. The economy's
 * {@code %lunavault_balance%} is the first of them.
 *
 * A namespace belongs to exactly one extension. Returning null from
 * {@link #resolve} means "not mine after all", and the core carries on down its
 * own providers rather than printing an empty value.
 */
public interface LunaPlaceholderExtension {
	/** The identifier prefixes this extension answers, lowercase and without percents. */
	Set<String> namespaces();

	/**
	 * @param params everything after the namespace, already lowercased
	 * @return the value, or null when this extension does not claim it
	 */
	String resolve(ServerPlayer player, String namespace, String params);

	/**
	 * Add the values worth publishing without being asked, for the callers that
	 * take a whole snapshot at once - a tab list rebuilding every row.
	 *
	 * Keys are full identifiers, {@code lunavault_balance} rather than
	 * {@code balance}. The default contributes nothing, which is right for an
	 * extension whose values are expensive or rarely wanted.
	 */
	default void contributeSnapshot(ServerPlayer player, Map<String, String> values) {
	}
}
