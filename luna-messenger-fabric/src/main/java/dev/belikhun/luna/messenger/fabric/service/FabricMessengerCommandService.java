package dev.belikhun.luna.messenger.fabric.service;

import java.util.Collection;
import java.util.UUID;

public final class FabricMessengerCommandService {

	private final FabricMessengerGateway gateway;

	public FabricMessengerCommandService(FabricMessengerGateway gateway) {
		this.gateway = gateway;
	}

	public CommandResult switchNetwork(UUID senderId, String senderName, String senderServer) {
		return asResult(gateway.switchNetwork(senderId, senderName, senderServer), "Đã gửi yêu cầu chuyển kênh mạng.");
	}

	public CommandResult switchServer(UUID senderId, String senderName, String senderServer) {
		return asResult(gateway.switchServer(senderId, senderName, senderServer), "Đã gửi yêu cầu chuyển kênh máy chủ.");
	}

	public CommandResult switchDirect(UUID senderId, String senderName, String senderServer, String targetName) {
		if (targetName == null || targetName.isBlank()) {
			return CommandResult.fail("Tên người chơi không hợp lệ.");
		}
		return asResult(gateway.switchDirect(senderId, senderName, senderServer, targetName), "Đã chuyển sang hội thoại riêng với " + targetName + ".");
	}

	public CommandResult sendDirect(UUID senderId, String senderName, String senderServer, String targetName, String content) {
		if (targetName == null || targetName.isBlank()) {
			return CommandResult.fail("Tên người chơi không hợp lệ.");
		}
		if (content == null || content.isBlank()) {
			return CommandResult.fail("Nội dung tin nhắn không được để trống.");
		}
		return asResult(gateway.sendDirect(senderId, senderName, senderServer, content, targetName), "Đã gửi tin nhắn riêng tới " + targetName + ".");
	}

	public CommandResult sendReply(UUID senderId, String senderName, String senderServer, String content) {
		if (content == null || content.isBlank()) {
			return CommandResult.fail("Nội dung tin nhắn không được để trống.");
		}
		return asResult(gateway.sendReply(senderId, senderName, senderServer, content), "Đã gửi tin nhắn trả lời.");
	}

	public CommandResult sendPoke(UUID senderId, String senderName, String senderServer, String targetName) {
		if (targetName == null || targetName.isBlank()) {
			return CommandResult.fail("Tên người chơi không hợp lệ.");
		}
		return asResult(gateway.sendPoke(senderId, senderName, senderServer, targetName), "Đã gửi poke tới " + targetName + ".");
	}

	public CommandResult sendChat(UUID senderId, String senderName, String senderServer, String content) {
		if (content == null || content.isBlank()) {
			return CommandResult.fail("Nội dung tin nhắn không được để trống.");
		}
		return asResult(gateway.sendChat(senderId, senderName, senderServer, content), "Đã gửi tin nhắn kênh hiện tại.");
	}

	public Collection<String> suggestDirectTargets(String partial, String senderName) {
		return gateway.suggestDirectTargets(partial, senderName);
	}

	private CommandResult asResult(boolean success, String successMessage) {
		if (success) {
			return CommandResult.ok(successMessage);
		}
		return CommandResult.fail("Không thể gửi yêu cầu messenger đến proxy.");
	}

	public record CommandResult(boolean success, String message) {
		public static CommandResult ok(String message) {
			return new CommandResult(true, message);
		}

		public static CommandResult fail(String message) {
			return new CommandResult(false, message);
		}
	}
}
