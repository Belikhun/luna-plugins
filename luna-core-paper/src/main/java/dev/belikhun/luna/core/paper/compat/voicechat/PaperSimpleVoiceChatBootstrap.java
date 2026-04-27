package dev.belikhun.luna.core.paper.compat.voicechat;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.belikhun.luna.core.api.compat.SimpleVoiceChatCompat;
import dev.belikhun.luna.core.api.logging.LunaLogger;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperSimpleVoiceChatBootstrap {
	private static final PaperSimpleVoiceChatProvider PROVIDER = new PaperSimpleVoiceChatProvider();

	private PaperSimpleVoiceChatBootstrap() {
	}

	public static void register(JavaPlugin plugin, LunaLogger logger) {
		BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
		if (service == null) {
			logger.warn("Simple Voice Chat đang bật nhưng BukkitVoicechatService chưa sẵn sàng. Bỏ qua voicechat placeholders.");
			return;
		}

		SimpleVoiceChatCompat.installProvider(PROVIDER);
		service.registerPlugin(new PaperSimpleVoiceChatPlugin(PROVIDER, logger));
		logger.audit("Đã đăng ký Luna Core vào Simple Voice Chat API trên Paper.");
	}

	public static void unregister() {
		PROVIDER.clear();
		SimpleVoiceChatCompat.clearProvider(PROVIDER);
	}
}
