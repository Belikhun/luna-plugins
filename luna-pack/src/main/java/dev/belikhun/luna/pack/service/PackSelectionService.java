package dev.belikhun.luna.pack.service;

import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import dev.belikhun.luna.pack.model.PackCatalogSnapshot;
import dev.belikhun.luna.pack.model.ResolvedPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PackSelectionService {
	private static final Comparator<ResolvedPack> ORDER = Comparator
		.comparingInt((ResolvedPack pack) -> pack.definition().priority())
		.thenComparing(pack -> pack.definition().normalizedName());

	public List<ResolvedPack> selectForServer(PackCatalogSnapshot snapshot, String serverName) {
		List<ResolvedPack> selected = new ArrayList<>();
		for (ResolvedPack pack : snapshot.resolvedPacks()) {
			if (!pack.available()) {
				continue;
			}
			if (!pack.definition().enabled()) {
				continue;
			}
			if (!pack.definition().matchesServer(serverName)) {
				continue;
			}
			selected.add(pack);
		}
		selected.sort(ORDER);
		return selected;
	}

	public Delta computeDelta(List<ResolvedPack> desired, Map<String, ResourcePackInfo> loadedByName) {
		Set<String> loadedNormalizedNames = loadedByName.keySet();
		Set<String> desiredNames = new LinkedHashSet<>();
		for (ResolvedPack pack : desired) {
			desiredNames.add(pack.definition().normalizedName());
		}

		List<ResolvedPack> toLoad = new ArrayList<>();
		for (ResolvedPack pack : desired) {
			if (!loadedNormalizedNames.contains(pack.definition().normalizedName())) {
				toLoad.add(pack);
			}
		}

		List<Map.Entry<String, ResourcePackInfo>> toUnload = new ArrayList<>();
		for (Map.Entry<String, ResourcePackInfo> loaded : loadedByName.entrySet()) {
			if (!desiredNames.contains(loaded.getKey())) {
				toUnload.add(loaded);
			}
		}

		return new Delta(toLoad, toUnload);
	}

	public record Delta(
		List<ResolvedPack> toLoad,
		List<Map.Entry<String, ResourcePackInfo>> toUnload
	) {
		public boolean isEmpty() {
			return toLoad.isEmpty() && toUnload.isEmpty();
		}
	}
}
