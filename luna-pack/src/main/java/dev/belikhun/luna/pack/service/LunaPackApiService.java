package dev.belikhun.luna.pack.service;

import dev.belikhun.luna.core.api.event.LunaEventManager;
import dev.belikhun.luna.pack.api.LunaPackApi;
import dev.belikhun.luna.pack.api.LunaPackDynamicProvider;
import dev.belikhun.luna.pack.api.LunaPackReloadEvent;
import dev.belikhun.luna.pack.api.LunaPackReloadListener;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackReloadReport;

import java.util.Objects;
import java.util.function.Supplier;

public final class LunaPackApiService implements LunaPackApi {
	@FunctionalInterface
	public interface ReloadAction {
		PackReloadReport reload();
	}

	private final PackDynamicRegistry dynamicRegistry;
	private final Supplier<PackCatalogSnapshot> snapshotSupplier;
	private final ReloadAction reloadAction;
	private final LunaEventManager reloadEventManager;

	public LunaPackApiService(
		PackDynamicRegistry dynamicRegistry,
		Supplier<PackCatalogSnapshot> snapshotSupplier,
		ReloadAction reloadAction,
		LunaEventManager reloadEventManager
	) {
		this.dynamicRegistry = Objects.requireNonNull(dynamicRegistry, "dynamicRegistry");
		this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
		this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
		this.reloadEventManager = Objects.requireNonNull(reloadEventManager, "reloadEventManager");
	}

	@Override
	public void registerDynamicProvider(String providerId, LunaPackDynamicProvider provider) {
		dynamicRegistry.register(providerId, provider);
	}

	@Override
	public void unregisterDynamicProvider(String providerId) {
		dynamicRegistry.unregister(providerId);
	}

	@Override
	public PackCatalogSnapshot snapshot() {
		return snapshotSupplier.get();
	}

	@Override
	public PackReloadReport reload() {
		return reloadAction.reload();
	}

	@Override
	public void addReloadListener(LunaPackReloadListener listener) {
		reloadEventManager.registerListener(LunaPackReloadEvent.class, listener);
	}

	@Override
	public void removeReloadListener(LunaPackReloadListener listener) {
		reloadEventManager.unregisterListener(LunaPackReloadEvent.class, listener);
	}

	public void emitReload(PackCatalogSnapshot previous, PackCatalogSnapshot current, PackReloadReport report) {
		reloadEventManager.dispatchEvent(new LunaPackReloadEvent(previous, current, report, System.currentTimeMillis()));
	}

	public void clearListeners() {
		reloadEventManager.clear();
	}
}
