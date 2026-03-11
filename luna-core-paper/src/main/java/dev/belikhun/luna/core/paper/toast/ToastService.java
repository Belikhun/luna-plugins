package dev.belikhun.luna.core.paper.toast;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface ToastService {
	ToastResult sendOneShot(Player player, String keyPrefix, Component title, Component subtitle);

	record ToastResult(boolean success, String failureReason) {
		public static ToastResult ok() {
			return new ToastResult(true, "");
		}

		public static ToastResult fail(String failureReason) {
			return new ToastResult(false, failureReason == null ? "unknown" : failureReason);
		}
	}
}
