package dev.belikhun.luna.auth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.auth.service.AuthService;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.function.Consumer;

public final class LogoutCommand implements SimpleCommand {
	private final AuthService authService;
	private final Consumer<Player> authStateSync;

	public LogoutCommand(AuthService authService, Consumer<Player> authStateSync) {
		this.authService = authService;
		this.authStateSync = authStateSync;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		if (!(source instanceof Player player)) {
			source.sendRichMessage("<red>❌ Lệnh này chỉ dùng trong game.</red>");
			return;
		}

		AuthService.AuthResult result = authService.logout(
			player.getUniqueId(),
			player.getUsername(),
			player.getRemoteAddress().getAddress().getHostAddress()
		);
		authStateSync.accept(player);
		if (result.success()) {
			player.disconnect(Component.text("Bạn đã đăng xuất phiên hiện tại."));
			return;
		}

		player.sendRichMessage("<red>" + result.message() + "</red>");
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}
}
