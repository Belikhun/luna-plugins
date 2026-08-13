package dev.belikhun.luna.legacy.vault;

import dev.belikhun.luna.legacy.messaging.PluginMessageReader;
import dev.belikhun.luna.legacy.messaging.PluginMessageWriter;

import java.util.ArrayList;
import java.util.List;

public final class VaultCacheRefresh {
	private final boolean clearAll;
	private final List<VaultPlayerSnapshot> snapshots;

	public VaultCacheRefresh(boolean clearAll, List<VaultPlayerSnapshot> snapshots) {
		this.clearAll = clearAll;
		this.snapshots = snapshots;
	}

	public boolean clearAll() {
		return clearAll;
	}

	public List<VaultPlayerSnapshot> snapshots() {
		return snapshots;
	}

	public void writeTo(PluginMessageWriter writer) {
		writer.writeBoolean(clearAll);
		writer.writeInt(snapshots == null ? 0 : snapshots.size());
		if (snapshots == null) {
			return;
		}

		for (VaultPlayerSnapshot snapshot : snapshots) {
			snapshot.writeTo(writer);
		}
	}

	public static VaultCacheRefresh readFrom(PluginMessageReader reader) {
		boolean clearAll = reader.readBoolean();
		int count = Math.max(0, reader.readInt());
		List<VaultPlayerSnapshot> snapshots = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			snapshots.add(VaultPlayerSnapshot.readFrom(reader));
		}
		return new VaultCacheRefresh(clearAll, snapshots);
	}
}
