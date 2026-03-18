package dev.belikhun.luna.pack.api;

import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.PackReloadReport;

public interface LunaPackApi {
	void registerDynamicProvider(String providerId, LunaPackDynamicProvider provider);

	void unregisterDynamicProvider(String providerId);

	PackCatalogSnapshot snapshot();

	PackReloadReport reload();

	void addReloadListener(LunaPackReloadListener listener);

	void removeReloadListener(LunaPackReloadListener listener);
}
