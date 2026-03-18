package dev.belikhun.luna.core.fabric.adapter.mc121x;

import dev.belikhun.luna.core.api.messaging.PluginMessageChannel;
import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.core.fabric.adapter.AbstractFabricFamilyNetworkingAdapter;
import dev.belikhun.luna.core.fabric.bootstrap.mc121x.Mc121xBootstrap;
import dev.belikhun.luna.core.fabric.messaging.FabricMessageTarget;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class Mc121xNetworkingAdapter extends AbstractFabricFamilyNetworkingAdapter {
	private static final String SERVER_PLAY_NETWORKING_CLASS = "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking";
	private static final String PACKET_BYTE_BUFS_CLASS = "net.fabricmc.fabric.api.networking.v1.PacketByteBufs";
	private static final String IDENTIFIER_CLASS = "net.minecraft.class_2960";

	private volatile Object bridgeIdentifier;

	@Override
	public FabricVersionFamily family() {
		return FabricVersionFamily.MC121X;
	}

	@Override
	public String adapterId() {
		return "fabric-networking-mc121x";
	}

	@Override
	public void initialize() {
		Mc121xBootstrap.bridge().setOutboundSender(this::sendOutbound);
		registerLegacyReceiver();
	}

	@Override
	public void shutdown() {
		unregisterLegacyReceiver();
		Mc121xBootstrap.bridge().clearOutboundSender();
	}

	private boolean sendOutbound(FabricMessageTarget target, PluginMessageChannel channel, byte[] payload) {
		Object server = Mc121xBootstrap.server();
		if (server == null) {
			return false;
		}

		Object recipient = resolveRecipient(server, target);
		if (recipient == null) {
			return false;
		}

		try {
			Class<?> networkingClass = Class.forName(SERVER_PLAY_NETWORKING_CLASS);
			Object identifier = ensureBridgeIdentifier();
			if (identifier == null) {
				return false;
			}

			Method canSend = findMethod(networkingClass, "canSend", 2);
			if (canSend != null) {
				Object canSendResult = canSend.invoke(null, recipient, identifier);
				if (!(canSendResult instanceof Boolean) || !((Boolean) canSendResult)) {
					return false;
				}
			}

			Object packet = createPacketBuffer();
			if (packet == null) {
				return false;
			}

			Method writeString = findMethod(packet.getClass(), "writeString", 1);
			Method writeByteArray = findMethod(packet.getClass(), "writeByteArray", 1);
			if (writeString == null || writeByteArray == null) {
				return false;
			}

			writeString.invoke(packet, channel.value());
			writeByteArray.invoke(packet, payload == null ? new byte[0] : payload);

			Method send = findMethod(networkingClass, "send", 3);
			if (send == null) {
				return false;
			}

			send.invoke(null, recipient, identifier, packet);
			return true;
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private Object resolveRecipient(Object server, FabricMessageTarget target) {
		Object playerManager = invokeNoArgs(server, "getPlayerManager");
		if (playerManager == null) {
			return null;
		}

		UUID playerId = target == null ? null : target.playerId();
		if (playerId != null) {
			Object player = invokeSingleArg(playerManager, "getPlayer", playerId);
			if (player != null) {
				return player;
			}
		}

		Object playerList = invokeNoArgs(playerManager, "getPlayerList");
		if (playerList instanceof List<?> list && !list.isEmpty()) {
			return list.get(0);
		}

		if (playerList instanceof Collection<?> collection && !collection.isEmpty()) {
			return collection.iterator().next();
		}

		return null;
	}

	private void registerLegacyReceiver() {
		try {
			Class<?> networkingClass = Class.forName(SERVER_PLAY_NETWORKING_CLASS);
			Method registerMethod = findMethod(networkingClass, "registerGlobalReceiver", 2);
			if (registerMethod == null) {
				return;
			}

			Object identifier = ensureBridgeIdentifier();
			if (identifier == null) {
				return;
			}

			Class<?> receiverType = registerMethod.getParameterTypes()[1];
			InvocationHandler handler = (proxy, method, args) -> {
				if (method.getDeclaringClass() == Object.class) {
					return method.invoke(this, args);
				}

				handleIncomingFromCallback(args);
				return null;
			};
			Object receiverProxy = Proxy.newProxyInstance(receiverType.getClassLoader(), new Class<?>[]{receiverType}, handler);
			registerMethod.invoke(null, identifier, receiverProxy);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private void unregisterLegacyReceiver() {
		Object identifier = bridgeIdentifier;
		if (identifier == null) {
			return;
		}

		try {
			Class<?> networkingClass = Class.forName(SERVER_PLAY_NETWORKING_CLASS);
			Method unregisterMethod = findMethod(networkingClass, "unregisterGlobalReceiver", 1);
			if (unregisterMethod != null) {
				unregisterMethod.invoke(null, identifier);
			}
		} catch (ReflectiveOperationException ignored) {
		} finally {
			bridgeIdentifier = null;
		}
	}

	private void handleIncomingFromCallback(Object[] callbackArgs) {
		if (callbackArgs == null || callbackArgs.length == 0) {
			return;
		}

		Object packetBuffer = null;
		Object server = null;
		for (Object arg : callbackArgs) {
			if (arg == null) {
				continue;
			}

			if (packetBuffer == null && hasMethod(arg.getClass(), "readString", 1) && hasMethod(arg.getClass(), "readByteArray", 0)) {
				packetBuffer = arg;
			}

			if (server == null && hasMethod(arg.getClass(), "execute", 1)) {
				server = arg;
			}
		}

		if (packetBuffer == null) {
			return;
		}

		try {
			Method readString = findMethod(packetBuffer.getClass(), "readString", 1);
			Method readByteArray = findMethod(packetBuffer.getClass(), "readByteArray", 0);
			if (readString == null || readByteArray == null) {
				return;
			}

			String channelValue = (String) readString.invoke(packetBuffer, 32767);
			byte[] payload = (byte[]) readByteArray.invoke(packetBuffer);
			PluginMessageChannel channel = PluginMessageChannel.of(channelValue);
			Runnable task = () -> Mc121xBootstrap.bridge().injectIncoming(channel, payload);

			if (server != null) {
				Method execute = findMethod(server.getClass(), "execute", 1);
				if (execute != null) {
					execute.invoke(server, task);
					return;
				}
			}

			task.run();
		} catch (Exception ignored) {
		}
	}

	private Object ensureBridgeIdentifier() {
		Object current = bridgeIdentifier;
		if (current != null) {
			return current;
		}

		try {
			Class<?> identifierClass = Class.forName(IDENTIFIER_CLASS);
			Object identifier = identifierClass.getConstructor(String.class, String.class).newInstance("luna", "bridge");
			bridgeIdentifier = identifier;
			return identifier;
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private Object createPacketBuffer() {
		try {
			Class<?> packetByteBufsClass = Class.forName(PACKET_BYTE_BUFS_CLASS);
			Method createMethod = packetByteBufsClass.getMethod("create");
			return createMethod.invoke(null);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private static Method findMethod(Class<?> type, String name, int parameterCount) {
		for (Method method : type.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
				return method;
			}
		}
		return null;
	}

	private static boolean hasMethod(Class<?> type, String name, int parameterCount) {
		return findMethod(type, name, parameterCount) != null;
	}

	private static Object invokeNoArgs(Object target, String methodName) {
		try {
			Method method = target.getClass().getMethod(methodName);
			return method.invoke(target);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private static Object invokeSingleArg(Object target, String methodName, Object argument) {
		for (Method method : target.getClass().getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
				continue;
			}

			Class<?> parameterType = method.getParameterTypes()[0];
			if (argument != null && !parameterType.isInstance(argument) && !parameterType.equals(argument.getClass())) {
				continue;
			}

			try {
				return method.invoke(target, argument);
			} catch (ReflectiveOperationException ignored) {
				return null;
			}
		}
		return null;
	}
}
