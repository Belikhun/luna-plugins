package dev.belikhun.luna.auth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.belikhun.luna.auth.service.AuthService;

import java.util.List;
import java.util.function.Consumer;

public final class RegisterCommand implements SimpleCommand {
	private final AuthService authService;
	private final Consumer<Player> authStateSync;

	public RegisterCommand(AuthService authService, Consumer<Player> authStateSync) {
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

		String[] args = invocation.arguments();
		if (args.length < 2) {
			player.sendRichMessage("<yellow>ℹ Cú pháp: <white>/register <mật_khẩu> <nhập_lại></white></yellow>");
			return;
		}

		AuthService.AuthResult result = authService.register(
			player.getUniqueId(),
			player.getUsername(),
			player.getRemoteAddress().getAddress().getHostAddress(),
			args[0],
			args[1]
		);
		if (result.success()) {
			authStateSync.accept(player);
			player.sendRichMessage("<green>" + result.message() + "</green>");
			return;
		}

		player.sendRichMessage("<red>" + result.message() + "</red>");
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}
}
