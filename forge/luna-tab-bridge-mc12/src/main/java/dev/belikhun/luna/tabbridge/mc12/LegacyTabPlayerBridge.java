package dev.belikhun.luna.tabbridge.mc12;

import dev.belikhun.luna.legacy.messaging.bus.PlayerBridge;
import dev.belikhun.luna.legacy.tabbridge.TabPlayerBridge;

import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Collection;
import java.util.UUID;

/**
 * The three extra calls TAB needs, on 1.12.2.
 *
 * It wraps the core's {@link PlayerBridge} rather than replacing it, so the five
 * shared operations still have exactly one implementation on this backend. A
 * second copy would be five more chances to disagree about what a player is, and
 * this mod would gain nothing by holding one.
 */
public final class LegacyTabPlayerBridge implements TabPlayerBridge<EntityPlayerMP> {
	private final PlayerBridge<EntityPlayerMP> delegate;

	public LegacyTabPlayerBridge(PlayerBridge<EntityPlayerMP> delegate) {
		if (delegate == null) {
			throw new IllegalArgumentException("delegate");
		}

		this.delegate = delegate;
	}

	/**
	 * The dimension's name, unnamespaced.
	 *
	 * 1.12.2 predates the `minecraft:` namespace on dimensions, so this answers
	 * `overworld` where a 1.20.1 backend answers `minecraft:overworld`. A TAB
	 * condition meant to cover both has to list both spellings; there is nothing to
	 * normalise towards, because Paper answers with the world *folder* name and is
	 * a third spelling again.
	 */
	@Override
	public String worldName(EntityPlayerMP player) {
		if (player == null || player.world == null || player.world.provider == null) {
			return "unknown";
		}

		return player.world.provider.getDimensionType().getName();
	}

	@Override
	public int gameModeId(EntityPlayerMP player) {
		if (player == null || player.interactionManager == null) {
			return 0;
		}

		return player.interactionManager.getGameType().getID();
	}

	@Override
	public boolean invisible(EntityPlayerMP player) {
		return player != null && player.isInvisible();
	}

	@Override
	public UUID idOf(EntityPlayerMP player) {
		return delegate.idOf(player);
	}

	@Override
	public String nameOf(EntityPlayerMP player) {
		return delegate.nameOf(player);
	}

	@Override
	public EntityPlayerMP byId(UUID id) {
		return delegate.byId(id);
	}

	@Override
	public EntityPlayerMP byName(String name) {
		return delegate.byName(name);
	}

	@Override
	public Collection<EntityPlayerMP> online() {
		return delegate.online();
	}

	@Override
	public void onServerThread(Runnable task) {
		delegate.onServerThread(task);
	}
}
