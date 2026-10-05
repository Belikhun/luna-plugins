package dev.belikhun.luna.core.api.placeholder;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The registry and the engine together, which is what a platform hands to the
 * plugins that publish placeholders.
 *
 * Registering through here rather than on the registry directly lets the
 * platform's adapters follow along: a PlaceholderAPI expansion per namespace
 * on Paper, the id list a tab bridge answers for, the tags a MiniPlaceholders
 * expansion knows.
 *
 * @param <P> the platform's player type
 */
public final class PlaceholderRuntime<P> {
	private final PlaceholderRegistry<P> registry;
	private final PlaceholderEngine<P> engine;
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
	private final Consumer<String> diagnostics;

	public PlaceholderRuntime(PlaceholderHost<P> host, Consumer<String> diagnostics) {
		this.diagnostics = diagnostics == null ? message -> {
		} : diagnostics;
		this.registry = new PlaceholderRegistry<>(this.diagnostics);
		this.engine = new PlaceholderEngine<>(registry, host, this.diagnostics);
	}

	public PlaceholderRegistry<P> registry() {
		return registry;
	}

	public PlaceholderEngine<P> engine() {
		return engine;
	}

	/**
	 * Publishes specs under an owner and tells the adapters something changed.
	 *
	 * @return how many specs were accepted
	 */
	public int register(Object owner, Collection<? extends PlaceholderSpec<P>> specs) {
		int accepted = registry.register(owner, specs);
		notifyChanged();
		return accepted;
	}

	/** Withdraws an owner's specs and tells the adapters. */
	public int unregister(Object owner) {
		int removed = registry.unregister(owner);

		if (removed > 0) {
			notifyChanged();
		}

		return removed;
	}

	/** Runs after every registration change; adapters sync their surfaces here. */
	public void onChange(Runnable listener) {
		if (listener != null) {
			changeListeners.add(listener);
		}
	}

	private void notifyChanged() {
		for (Runnable listener : changeListeners) {
			try {
				listener.run();
			} catch (RuntimeException exception) {
				diagnostics.accept("Placeholder adapter báo lỗi khi đồng bộ: " + exception);
			}
		}
	}
}
