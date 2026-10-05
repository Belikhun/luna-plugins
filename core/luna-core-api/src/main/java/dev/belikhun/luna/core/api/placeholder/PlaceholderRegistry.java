package dev.belikhun.luna.core.api.placeholder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Every placeholder spec a process knows, indexed by namespace.
 *
 * Specs are registered by an owner so a plugin can withdraw all of its own on
 * disable or reload. Matching prefers the pattern with the most literal words,
 * so {@code tps_bar_only[_<width>]} beats {@code tps_bar[_<width>]} for
 * {@code tps_bar_only_40}; among equals the first registered wins. Two owners
 * registering the same pattern in one namespace is reported through the
 * diagnostics sink and the second is ignored; the same owner registering again
 * replaces its earlier spec, which is what a reload does.
 *
 * @param <P> the platform's player type
 */
public final class PlaceholderRegistry<P> {
	private final Map<String, List<Entry<P>>> byNamespace = new ConcurrentHashMap<>();
	private final Consumer<String> diagnostics;

	/** A spec together with who registered it. */
	public record Entry<P>(Object owner, PlaceholderSpec<P> spec) {
	}

	/** A spec that matched a ref, with the parameters the match bound. */
	public record Match<P>(PlaceholderSpec<P> spec, Map<String, String> params) {
	}

	public PlaceholderRegistry(Consumer<String> diagnostics) {
		this.diagnostics = diagnostics == null ? message -> {
		} : diagnostics;
	}

	/**
	 * Registers specs under an owner.
	 *
	 * @param owner the registering object, used to unregister as a group
	 * @param specs the specs
	 * @return how many were added or replaced
	 */
	public int register(Object owner, Collection<? extends PlaceholderSpec<P>> specs) {
		int count = 0;

		for (PlaceholderSpec<P> spec : specs) {
			if (register(owner, spec)) {
				count++;
			}
		}

		return count;
	}

	/** Registers one spec; see {@link #register(Object, Collection)}. */
	public boolean register(Object owner, PlaceholderSpec<P> spec) {
		if (owner == null || spec == null) {
			return false;
		}

		List<Entry<P>> entries = byNamespace.computeIfAbsent(spec.namespace(), ignored -> new CopyOnWriteArrayList<>());

		for (int index = 0; index < entries.size(); index++) {
			Entry<P> existing = entries.get(index);

			if (!existing.spec().key().equals(spec.key())) {
				continue;
			}

			if (existing.owner() == owner) {
				entries.set(index, new Entry<>(owner, spec));
				return true;
			}

			diagnostics.accept("Placeholder " + spec.canonical() + " từ " + describe(owner)
				+ " bị bỏ qua: " + describe(existing.owner()) + " đã đăng ký cùng mẫu này trước.");
			return false;
		}

		entries.add(new Entry<>(owner, spec));
		return true;
	}

	/**
	 * Withdraws every spec an owner registered.
	 *
	 * @return how many were removed
	 */
	public int unregister(Object owner) {
		int removed = 0;

		for (Map.Entry<String, List<Entry<P>>> namespace : byNamespace.entrySet()) {
			List<Entry<P>> entries = namespace.getValue();
			List<Entry<P>> mine = new ArrayList<>();

			for (Entry<P> entry : entries) {
				if (entry.owner() == owner) {
					mine.add(entry);
				}
			}

			entries.removeAll(mine);
			removed += mine.size();
		}

		byNamespace.values().removeIf(List::isEmpty);
		return removed;
	}

	/** Whether any spec claims the namespace. */
	public boolean claims(String namespace) {
		if (namespace == null) {
			return false;
		}

		List<Entry<P>> entries = byNamespace.get(namespace.toLowerCase(Locale.ROOT));
		return entries != null && !entries.isEmpty();
	}

	/** Every claimed namespace, sorted. */
	public Set<String> namespaces() {
		Set<String> names = new TreeSet<>();

		for (Map.Entry<String, List<Entry<P>>> namespace : byNamespace.entrySet()) {
			if (!namespace.getValue().isEmpty()) {
				names.add(namespace.getKey());
			}
		}

		return Collections.unmodifiableSet(names);
	}

	/** Every registered entry, grouped by namespace in sorted order. */
	public Map<String, List<Entry<P>>> entries() {
		Map<String, List<Entry<P>>> copy = new LinkedHashMap<>();

		for (String namespace : namespaces()) {
			copy.put(namespace, List.copyOf(byNamespace.getOrDefault(namespace, List.of())));
		}

		return copy;
	}

	/** Every spec in one namespace, in registration order. */
	public List<PlaceholderSpec<P>> specs(String namespace) {
		List<PlaceholderSpec<P>> specs = new ArrayList<>();

		for (Entry<P> entry : byNamespace.getOrDefault(namespace == null ? "" : namespace.toLowerCase(Locale.ROOT), List.of())) {
			specs.add(entry.spec());
		}

		return specs;
	}

	/**
	 * Finds the spec for a ref.
	 *
	 * @param ref the placeholder as written
	 * @return the best match, or empty when nothing in the namespace fits
	 */
	public Optional<Match<P>> match(PlaceholderRef ref) {
		if (ref == null || ref.namespace().isEmpty()) {
			return Optional.empty();
		}

		List<Entry<P>> entries = byNamespace.get(ref.namespace());

		if (entries == null || entries.isEmpty()) {
			return Optional.empty();
		}

		Match<P> best = null;
		int bestLiterals = -1;

		for (Entry<P> entry : entries) {
			KeyPattern key = entry.spec().key();

			if (key.literalCount() <= bestLiterals) {
				continue;
			}

			Optional<Map<String, String>> bound = key.match(ref.tokens(), ref.args());

			if (bound.isPresent()) {
				best = new Match<>(entry.spec(), bound.get());
				bestLiterals = key.literalCount();
			}
		}

		return Optional.ofNullable(best);
	}

	private static String describe(Object owner) {
		if (owner == null) {
			return "?";
		}

		if (owner instanceof String text) {
			return text;
		}

		return owner.getClass().getSimpleName();
	}
}
