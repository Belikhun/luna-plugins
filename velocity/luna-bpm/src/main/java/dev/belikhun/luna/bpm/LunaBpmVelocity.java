package dev.belikhun.luna.bpm;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * Network-wide Better Player Model server. One copy on the proxy replaces PaperBPM on
 * every backend: models live in this plugin's folder, a player's choice follows them
 * across servers, and each Paper backend only runs the tiny luna-bpm bridge.
 */
@Plugin(
	id = "luna-bpm",
	name = "LunaBPM",
	version = "1.0.0",
	description = "Network-wide Better Player Model (YSM) server: models, crypto handshake and sync on the proxy.",
	authors = {"belikhun", "Zhoporez (PaperBPM, MIT)"}
)
public final class LunaBpmVelocity {

	public static final MinecraftChannelIdentifier BPM_CHANNEL = MinecraftChannelIdentifier.from("better_player_model:2_6_0");
	public static final MinecraftChannelIdentifier BRIDGE_CHANNEL = MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);
	public static final MinecraftChannelIdentifier REGISTER_CHANNEL = MinecraftChannelIdentifier.from("minecraft:register");

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;
	private BpmService service;

	@Inject
	public LunaBpmVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onInitialize(ProxyInitializeEvent event) {
		BpmConfig config = BpmConfig.load(dataDirectory, logger);
		service = new BpmService(this, proxy, logger, dataDirectory, config);
		proxy.getChannelRegistrar().register(BPM_CHANNEL, BRIDGE_CHANNEL);
		proxy.getCommandManager().register(
			proxy.getCommandManager().metaBuilder("bpm").aliases("lunabpm").plugin(this).build(),
			new BpmCommand()
		);
		service.start();
		logger.info("LunaBPM ready: models in {}, channel {}", dataDirectory.resolve("models"), BPM_CHANNEL.getId());
	}

	@Subscribe
	public void onShutdown(ProxyShutdownEvent event) {
		if (service != null) {
			service.stop();
		}
	}

	@Subscribe(order = PostOrder.FIRST)
	public void onPluginMessage(PluginMessageEvent event) {
		boolean bpm = event.getIdentifier().equals(BPM_CHANNEL);
		boolean bridge = event.getIdentifier().equals(BRIDGE_CHANNEL);
		if (!bpm && !bridge) {
			return;
		}
		// Neither channel is ever forwarded: the proxy is the BPM server, and the bridge
		// channel is ours alone. A leftover PaperBPM on a backend is silenced this way too.
		event.setResult(PluginMessageEvent.ForwardResult.handled());
		if (service == null) {
			return;
		}

		byte[] data = event.getData();
		if (event.getSource() instanceof Player player) {
			if (bpm) {
				service.onClientPacket(player, data);
			}
			return;
		}
		if (event.getSource() instanceof ServerConnection connection && bridge) {
			service.onBridgeMessage(connection.getPlayer(), connection.getServerInfo().getName(), data);
		}
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		if (service != null) {
			service.onServerConnected(event.getPlayer());
		}
	}

	@Subscribe
	public void onDisconnect(DisconnectEvent event) {
		if (service != null) {
			service.onDisconnect(event.getPlayer());
		}
	}

	private final class BpmCommand implements SimpleCommand {
		@Override
		public void execute(Invocation invocation) {
			String[] args = invocation.arguments();
			if (args.length == 0 || !(args[0].equalsIgnoreCase("reload") || args[0].equalsIgnoreCase("status"))) {
				invocation.source().sendMessage(Component.text("Dùng: /bpm reload | /bpm status", NamedTextColor.YELLOW));
				return;
			}
			if (args[0].equalsIgnoreCase("status")) {
				invocation.source().sendMessage(Component.text(service.statusLine(), NamedTextColor.AQUA));
				return;
			}
			invocation.source().sendMessage(Component.text("[BPM] Đang nạp lại model…", NamedTextColor.GRAY));
			service.reload(summary -> invocation.source().sendMessage(Component.text("[BPM] " + summary, NamedTextColor.GREEN)));
		}

		@Override
		public boolean hasPermission(Invocation invocation) {
			return invocation.source().hasPermission("bpm.command");
		}

		@Override
		public List<String> suggest(Invocation invocation) {
			return List.of("reload", "status");
		}
	}
}
