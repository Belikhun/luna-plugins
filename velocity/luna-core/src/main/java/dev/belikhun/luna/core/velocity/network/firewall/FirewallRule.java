package dev.belikhun.luna.core.velocity.network.firewall;

import java.util.List;

/**
 * One operator rule: allow or deny the connections whose address is in
 * {@code addresses} and in one of {@code countries}. An empty list matches any
 * address (or any country); a rule naming neither matches nothing, so an empty
 * rule can never refuse the whole network.
 *
 * An allow rule lets a connection past the deny rules and the automatic-ban
 * detectors; a network ban still applies to it.
 */
public record FirewallRule(
	String id,
	String action,
	boolean enabled,
	List<String> addresses,
	List<String> countries,
	String note,
	String createdBy,
	long createdAt,
	long updatedAt,
	long hits,
	long lastHitAt
) {
	public boolean allows() {
		return "allow".equals(action);
	}

	/** Whether the rule names anything to match at all. */
	public boolean matchesSomething() {
		return !addresses.isEmpty() || !countries.isEmpty();
	}

	public FirewallRule withHits(long nextHits, long nextLastHitAt) {
		return new FirewallRule(id, action, enabled, addresses, countries, note, createdBy, createdAt, updatedAt, nextHits, nextLastHitAt);
	}
}
