package dev.belikhun.luna.core.api.messaging;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public final class StandardPluginMessenger<OWNER, SOURCE> implements PluginMessenger<OWNER, SOURCE> {
	private final Map<PluginMessageChannel, Set<PluginMessageListenerRegistration<OWNER, SOURCE>>> incomingByChannel;
	private final Map<OWNER, Set<PluginMessageListenerRegistration<OWNER, SOURCE>>> incomingByOwner;
	private final Map<PluginMessageChannel, Set<OWNER>> outgoingByChannel;
	private final Map<OWNER, Set<PluginMessageChannel>> outgoingByOwner;
	private final Object incomingLock;
	private final Object outgoingLock;
	private final BiConsumer<PluginMessageListenerRegistration<OWNER, SOURCE>, Throwable> exceptionHandler;

	public StandardPluginMessenger() {
		this((registration, throwable) -> {
		});
	}

	public StandardPluginMessenger(BiConsumer<PluginMessageListenerRegistration<OWNER, SOURCE>, Throwable> exceptionHandler) {
		this.incomingByChannel = new HashMap<>();
		this.incomingByOwner = new HashMap<>();
		this.outgoingByChannel = new HashMap<>();
		this.outgoingByOwner = new HashMap<>();
		this.incomingLock = new Object();
		this.outgoingLock = new Object();
		this.exceptionHandler = exceptionHandler == null ? (registration, throwable) -> {
		} : exceptionHandler;
	}

	@Override
	public boolean isReservedChannel(PluginMessageChannel channel) {
		return requireChannel(channel).isReserved();
	}

	@Override
	public void registerOutgoingPluginChannel(OWNER owner, PluginMessageChannel channel) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);
		if (safeChannel.isReserved()) {
			throw new ReservedPluginMessageChannelException(safeChannel);
		}

		synchronized (outgoingLock) {
			Set<OWNER> owners = outgoingByChannel.computeIfAbsent(safeChannel, ignored -> new LinkedHashSet<>());
			owners.add(safeOwner);
			outgoingByOwner.computeIfAbsent(safeOwner, ignored -> new LinkedHashSet<>()).add(safeChannel);
		}
	}

	@Override
	public void unregisterOutgoingPluginChannel(OWNER owner, PluginMessageChannel channel) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);

		synchronized (outgoingLock) {
			removeOutgoing(safeOwner, safeChannel);
		}
	}

	@Override
	public void unregisterOutgoingPluginChannel(OWNER owner) {
		OWNER safeOwner = requireOwner(owner);

		synchronized (outgoingLock) {
			Set<PluginMessageChannel> channels = outgoingByOwner.remove(safeOwner);
			if (channels == null) {
				return;
			}

			for (PluginMessageChannel channel : Set.copyOf(channels)) {
				removeOutgoing(safeOwner, channel);
			}
		}
	}

	@Override
	public PluginMessageListenerRegistration<OWNER, SOURCE> registerIncomingPluginChannel(OWNER owner, PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);
		PluginMessageHandler<SOURCE> safeHandler = Objects.requireNonNull(handler, "handler");
		if (safeChannel.isReserved()) {
			throw new ReservedPluginMessageChannelException(safeChannel);
		}

		PluginMessageListenerRegistration<OWNER, SOURCE> registration = new PluginMessageListenerRegistration<>(this, safeOwner, safeChannel, safeHandler);
		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> byChannel = incomingByChannel.computeIfAbsent(safeChannel, ignored -> new LinkedHashSet<>());
			if (byChannel.contains(registration)) {
				throw new IllegalArgumentException("This registration already exists");
			}

			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> byOwner = incomingByOwner.computeIfAbsent(safeOwner, ignored -> new LinkedHashSet<>());
			if (byOwner.contains(registration)) {
				throw new IllegalArgumentException("This registration already exists");
			}

			byChannel.add(registration);
			byOwner.add(registration);
		}
		return registration;
	}

	@Override
	public void unregisterIncomingPluginChannel(OWNER owner, PluginMessageChannel channel, PluginMessageHandler<SOURCE> handler) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);
		PluginMessageHandler<SOURCE> safeHandler = Objects.requireNonNull(handler, "handler");

		removeIncoming(new PluginMessageListenerRegistration<>(this, safeOwner, safeChannel, safeHandler));
	}

	@Override
	public void unregisterIncomingPluginChannel(OWNER owner, PluginMessageChannel channel) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByOwner.get(safeOwner);
			if (registrations == null) {
				return;
			}

			for (PluginMessageListenerRegistration<OWNER, SOURCE> registration : Set.copyOf(registrations)) {
				if (registration.getChannel().equals(safeChannel)) {
					removeIncoming(registration);
				}
			}
		}
	}

	@Override
	public void unregisterIncomingPluginChannel(OWNER owner) {
		OWNER safeOwner = requireOwner(owner);

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByOwner.get(safeOwner);
			if (registrations == null) {
				return;
			}

			for (PluginMessageListenerRegistration<OWNER, SOURCE> registration : Set.copyOf(registrations)) {
				removeIncoming(registration);
			}
		}
	}

	@Override
	public Set<PluginMessageChannel> getOutgoingChannels() {
		synchronized (outgoingLock) {
			return Set.copyOf(outgoingByChannel.keySet());
		}
	}

	@Override
	public Set<PluginMessageChannel> getOutgoingChannels(OWNER owner) {
		OWNER safeOwner = requireOwner(owner);

		synchronized (outgoingLock) {
			Set<PluginMessageChannel> channels = outgoingByOwner.get(safeOwner);
			return channels == null ? Set.of() : Set.copyOf(channels);
		}
	}

	@Override
	public Set<PluginMessageChannel> getIncomingChannels() {
		synchronized (incomingLock) {
			return Set.copyOf(incomingByChannel.keySet());
		}
	}

	@Override
	public Set<PluginMessageChannel> getIncomingChannels(OWNER owner) {
		OWNER safeOwner = requireOwner(owner);

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByOwner.get(safeOwner);
			if (registrations == null) {
				return Set.of();
			}

			Set<PluginMessageChannel> channels = new LinkedHashSet<>();
			for (PluginMessageListenerRegistration<OWNER, SOURCE> registration : registrations) {
				channels.add(registration.getChannel());
			}
			return Set.copyOf(channels);
		}
	}

	@Override
	public Set<PluginMessageListenerRegistration<OWNER, SOURCE>> getIncomingChannelRegistrations(OWNER owner) {
		OWNER safeOwner = requireOwner(owner);

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByOwner.get(safeOwner);
			return registrations == null ? Set.of() : Set.copyOf(registrations);
		}
	}

	@Override
	public Set<PluginMessageListenerRegistration<OWNER, SOURCE>> getIncomingChannelRegistrations(PluginMessageChannel channel) {
		PluginMessageChannel safeChannel = requireChannel(channel);

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByChannel.get(safeChannel);
			return registrations == null ? Set.of() : Set.copyOf(registrations);
		}
	}

	@Override
	public Set<PluginMessageListenerRegistration<OWNER, SOURCE>> getIncomingChannelRegistrations(OWNER owner, PluginMessageChannel channel) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByOwner.get(safeOwner);
			if (registrations == null) {
				return Set.of();
			}

			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> filtered = new LinkedHashSet<>();
			for (PluginMessageListenerRegistration<OWNER, SOURCE> registration : registrations) {
				if (registration.getChannel().equals(safeChannel)) {
					filtered.add(registration);
				}
			}
			return Set.copyOf(filtered);
		}
	}

	@Override
	public boolean isRegistrationValid(PluginMessageListenerRegistration<OWNER, SOURCE> registration) {
		PluginMessageListenerRegistration<OWNER, SOURCE> safeRegistration = Objects.requireNonNull(registration, "registration");

		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = incomingByOwner.get(safeRegistration.getOwner());
			return registrations != null && registrations.contains(safeRegistration);
		}
	}

	@Override
	public boolean isIncomingChannelRegistered(OWNER owner, PluginMessageChannel channel) {
		return !getIncomingChannelRegistrations(owner, channel).isEmpty();
	}

	@Override
	public boolean isOutgoingChannelRegistered(OWNER owner, PluginMessageChannel channel) {
		OWNER safeOwner = requireOwner(owner);
		PluginMessageChannel safeChannel = requireChannel(channel);

		synchronized (outgoingLock) {
			Set<PluginMessageChannel> channels = outgoingByOwner.get(safeOwner);
			return channels != null && channels.contains(safeChannel);
		}
	}

	@Override
	public PluginMessageDispatchResult dispatchIncomingMessage(SOURCE source, PluginMessageChannel channel, byte[] payload) {
		PluginMessageChannel safeChannel = requireChannel(channel);
		Objects.requireNonNull(payload, "payload");

		Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrations = getIncomingChannelRegistrations(safeChannel);
		if (registrations.isEmpty()) {
			return PluginMessageDispatchResult.PASS_THROUGH;
		}

		PluginMessageDispatchResult aggregate = PluginMessageDispatchResult.PASS_THROUGH;
		for (PluginMessageListenerRegistration<OWNER, SOURCE> registration : registrations) {
			try {
				PluginMessageDispatchResult result = registration.getHandler().handle(new PluginMessageContext<>(safeChannel, source, payload));
				if (result == PluginMessageDispatchResult.HANDLED) {
					aggregate = PluginMessageDispatchResult.HANDLED;
				}
			} catch (Throwable throwable) {
				exceptionHandler.accept(registration, throwable);
			}
		}

		return aggregate;
	}

	@Override
	public void clear() {
		synchronized (incomingLock) {
			incomingByChannel.clear();
			incomingByOwner.clear();
		}

		synchronized (outgoingLock) {
			outgoingByChannel.clear();
			outgoingByOwner.clear();
		}
	}

	private OWNER requireOwner(OWNER owner) {
		return Objects.requireNonNull(owner, "owner");
	}

	private PluginMessageChannel requireChannel(PluginMessageChannel channel) {
		return Objects.requireNonNull(channel, "channel");
	}

	private void removeIncoming(PluginMessageListenerRegistration<OWNER, SOURCE> registration) {
		synchronized (incomingLock) {
			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrationsByChannel = incomingByChannel.get(registration.getChannel());
			if (registrationsByChannel != null) {
				registrationsByChannel.remove(registration);
				if (registrationsByChannel.isEmpty()) {
					incomingByChannel.remove(registration.getChannel());
				}
			}

			Set<PluginMessageListenerRegistration<OWNER, SOURCE>> registrationsByOwner = incomingByOwner.get(registration.getOwner());
			if (registrationsByOwner != null) {
				registrationsByOwner.remove(registration);
				if (registrationsByOwner.isEmpty()) {
					incomingByOwner.remove(registration.getOwner());
				}
			}
		}
	}

	private void removeOutgoing(OWNER owner, PluginMessageChannel channel) {
		Set<OWNER> owners = outgoingByChannel.get(channel);
		if (owners != null) {
			owners.remove(owner);
			if (owners.isEmpty()) {
				outgoingByChannel.remove(channel);
			}
		}

		Set<PluginMessageChannel> channels = outgoingByOwner.get(owner);
		if (channels != null) {
			channels.remove(channel);
			if (channels.isEmpty()) {
				outgoingByOwner.remove(owner);
			}
		}
	}
}
