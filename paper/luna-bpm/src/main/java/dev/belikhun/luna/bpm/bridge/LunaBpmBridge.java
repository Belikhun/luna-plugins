package dev.belikhun.luna.bpm.bridge;

import dev.belikhun.luna.bpm.BridgeProtocol;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.DataInputStream;
import java.util.List;
import java.util.UUID;

/**
 * The backend half of luna-bpm. The Better Player Model server runs on the proxy; this
 * plugin only reports what the proxy cannot see: each player's entity id and world, and
 * who is tracking whom. It also swings an arm when the proxy asks (C2SSwingArmPacket),
 * and reports the entities a model draws for its owner: projectiles the owner shot
 * (arrows, tridents, fireworks, fishing hooks) and the vehicle the owner rides.
 * <p>
 * A message to the proxy has to ride a player's connection, and Paper only sends it if
 * that "client" registered the channel; the proxy registers it for every player it
 * connects, then sends REQUEST, which this plugin answers with a full report.
 */
public final class LunaBpmBridge extends JavaPlugin implements Listener, PluginMessageListener {

	@Override
	public void onEnable() {
		getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
		getServer().getMessenger().registerIncomingPluginChannel(this, BridgeProtocol.CHANNEL, this);
		getServer().getPluginManager().registerEvents(this, this);
		getLogger().info("Bridge ready on " + BridgeProtocol.CHANNEL + " (protocol " + BridgeProtocol.VERSION + ")");
	}

	@Override
	public void onDisable() {
		getServer().getMessenger().unregisterIncomingPluginChannel(this, BridgeProtocol.CHANNEL, this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this, BridgeProtocol.CHANNEL);
	}

	private boolean canSend(Player carrier) {
		return carrier != null && carrier.isOnline() && carrier.getListeningPluginChannels().contains(BridgeProtocol.CHANNEL);
	}

	/** Send through the first player whose connection can carry it. */
	private void send(byte[] data, Player... carriers) {
		for (Player carrier : carriers) {
			if (canSend(carrier)) {
				carrier.sendPluginMessage(this, BridgeProtocol.CHANNEL, data);
				return;
			}
		}
	}

	private void sendState(Player player) {
		send(BridgeProtocol.state(player.getUniqueId(), player.getEntityId(), player.getWorld().getName()), player);
	}

	private static Player shooterOf(Entity entity) {
		return entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
	}

	/** The player in the first seat, who drives the vehicle and whose model it wears. */
	private static Player riderOf(Entity entity) {
		List<Entity> passengers = entity.getPassengers();
		return !passengers.isEmpty() && passengers.get(0) instanceof Player rider ? rider : null;
	}

	private void announce(int kind, Entity entity, Player owner, Player receiver) {
		send(BridgeProtocol.entityModel(kind, receiver.getUniqueId(), owner.getUniqueId(), entity.getEntityId()), receiver, owner);
	}

	/** Tell the owner and everyone tracking the entity to draw it with the owner's model. */
	private void announceToAll(int kind, Entity entity, Player owner) {
		announce(kind, entity, owner, owner);
		for (Player viewer : entity.getTrackedBy()) {
			if (viewer != owner) {
				announce(kind, entity, owner, viewer);
			}
		}
	}

	private void announceVehicleOf(Player player) {
		Entity vehicle = player.getVehicle();
		if (vehicle != null && vehicle.isValid() && riderOf(vehicle) == player) {
			announceToAll(BridgeProtocol.KIND_VEHICLE, vehicle, player);
		}
	}

	/** HELLO, the player's state, and every tracking pair the player is part of. */
	private void sendFullReport(Player player) {
		send(BridgeProtocol.hello(), player);
		sendState(player);
		UUID self = player.getUniqueId();
		for (Player other : player.getWorld().getPlayers()) {
			if (other == player) {
				continue;
			}
			if (other.getTrackedBy().contains(player)) {
				send(BridgeProtocol.pair(BridgeProtocol.TRACK, self, other.getUniqueId()), player, other);
			}
			if (player.getTrackedBy().contains(other)) {
				send(BridgeProtocol.pair(BridgeProtocol.TRACK, other.getUniqueId(), self), player, other);
			}
		}

		// model-drawn entities: the player's own vehicle, and every arrow or ridden
		// mount around them that the player can see
		announceVehicleOf(player);
		double range = Math.max(4, getServer().getViewDistance()) * 16.0;
		for (Entity entity : player.getNearbyEntities(range, range, range)) {
			if (!entity.getTrackedBy().contains(player)) {
				continue;
			}
			Player shooter = shooterOf(entity);
			if (shooter != null && shooter.isOnline()) {
				announce(BridgeProtocol.KIND_PROJECTILE, entity, shooter, player);
			}
			Player rider = riderOf(entity);
			if (rider != null && rider != player) {
				announce(BridgeProtocol.KIND_VEHICLE, entity, rider, player);
			}
		}
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (!BridgeProtocol.CHANNEL.equals(channel) || message.length == 0) {
			return;
		}
		try (DataInputStream in = BridgeProtocol.reader(message)) {
			int type = in.readUnsignedByte();
			if (type == BridgeProtocol.REQUEST) {
				sendFullReport(player);
			} else if (type == BridgeProtocol.REFRESH_VEHICLE) {
				announceVehicleOf(player);
			} else if (type == BridgeProtocol.SWING) {
				int hand = in.readInt();
				if (hand == 0) {
					player.swingMainHand();
				} else if (hand == 1) {
					player.swingOffHand();
				}
			}
		} catch (Exception e) {
			getLogger().warning("Bad message from the proxy for " + player.getName() + ": " + e.getMessage());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		// the proxy's REQUEST normally answers this; a late register just means it comes then
		getServer().getScheduler().runTaskLater(this, () -> {
			if (canSend(player)) {
				sendFullReport(player);
			}
		}, 20L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		getServer().getScheduler().runTaskLater(this, () -> {
			if (player.isOnline()) {
				sendState(player);
			}
		}, 2L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChangedWorld(PlayerChangedWorldEvent event) {
		sendState(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTrack(PlayerTrackEntityEvent event) {
		Player viewer = event.getPlayer();
		Entity entity = event.getEntity();
		if (entity instanceof Player target && target != viewer) {
			send(BridgeProtocol.pair(BridgeProtocol.TRACK, viewer.getUniqueId(), target.getUniqueId()), viewer, target);
		}
		Player shooter = shooterOf(entity);
		if (shooter != null && shooter.isOnline()) {
			announce(BridgeProtocol.KIND_PROJECTILE, entity, shooter, viewer);
		}
		Player rider = riderOf(entity);
		if (rider != null && rider != viewer) {
			announce(BridgeProtocol.KIND_VEHICLE, entity, rider, viewer);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onLaunch(ProjectileLaunchEvent event) {
		Projectile projectile = event.getEntity();
		Player shooter = shooterOf(projectile);
		if (shooter == null) {
			return;
		}
		// next tick: the projectile is in the world and its first trackers are known
		getServer().getScheduler().runTask(this, () -> {
			if (projectile.isValid() && shooter.isOnline()) {
				announceToAll(BridgeProtocol.KIND_PROJECTILE, projectile, shooter);
			}
		});
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMount(EntityMountEvent event) {
		if (!(event.getEntity() instanceof Player player)) {
			return;
		}
		// next tick: the passenger list is final then
		getServer().getScheduler().runTask(this, () -> {
			if (player.isOnline()) {
				announceVehicleOf(player);
			}
		});
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onUntrack(PlayerUntrackEntityEvent event) {
		if (event.getEntity() instanceof Player target && target != event.getPlayer()) {
			Player viewer = event.getPlayer();
			send(BridgeProtocol.pair(BridgeProtocol.UNTRACK, viewer.getUniqueId(), target.getUniqueId()), viewer, target);
		}
	}
}
