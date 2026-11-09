package dev.belikhun.luna.bpm;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YsmCrypt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The Better Player Model server protocol, run on the proxy.
 * <p>
 * Ported from PaperBPM's YsmSessionManager (Zhop0rez, MIT). The wire behaviour is the
 * same; what changed is where the facts come from:
 * <ul>
 *   <li>entity id, world and tracking come from the luna-bpm bridge on the player's
 *       current backend instead of the Bukkit API;</li>
 *   <li>the model choice lives in {@link SelectionStore} (per UUID, network-wide)
 *       instead of a backend's PersistentDataContainer;</li>
 *   <li>each time a player lands on a backend the handshake runs again, exactly as a
 *       fresh PaperBPM on that backend would do (the client supports re-handshakes).</li>
 *   <li>added over PaperBPM: the entities a model draws for its owner, projectiles the
 *       owner shot and the vehicle the owner rides (S2CSyncProjectileModelPacket /
 *       S2CSyncVehicleModelPacket), as the original server mod sends them.</li>
 * </ul>
 * All session state is touched on one thread ({@link #net}); event handlers only post
 * work to it. Model compilation runs on {@link #workers} and posts its result back.
 */
public final class BpmService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int UPLOAD_CHUNK_SIZE = 16_000;
	private static final int MAX_CLIENT_UPLOAD_BYTES = 512 * 1024 * 1024;
	private static final int MAX_ACTIVE_UPLOADS_PER_PLAYER = 1;
	private static final int MAX_ACTIVE_UPLOAD_SESSIONS = 8;
	private static final long UPLOAD_SESSION_TIMEOUT_MS = 120_000L;
	private static final long UPLOAD_RATE_WINDOW_MS = 1_000L;
	private static final int MAX_MODEL_ID_LENGTH = 256;
	private static final int MAX_FILE_NAME_LENGTH = 256;
	private static final int MAX_EXPRESSION_VALUES = 64;
	private static final short MODEL_SWITCH_FLAG = 2048;
	private static final long TICK_MS = 50L;
	private static final byte[] CLIENT_KEY = createClientKey();
	private static final byte[] SERVER_KEY = new byte[56];

	/** Per online player. Lives from the first backend connect until disconnect. */
	static final class Session {
		final UUID id;
		final String name;
		// handshake, reset on every backend switch
		int step = 0;
		long connectGeneration = 0;
		byte[] key1;
		byte[] clientKey = Arrays.copyOf(CLIENT_KEY, CLIENT_KEY.length);
		byte[] clientNextKey;
		final Queue<PendingTransfer> transferQueue = new ConcurrentLinkedQueue<>();
		PendingTransfer currentTransfer;
		long tokens = 0;
		long resyncGeneration = 0;
		boolean resyncAfterTransfers = false;
		// facts from the bridge on the current backend
		String server = "";
		int entityId = -1;
		String world = "";
		boolean bridged = false;
		// not persisted: the extra animation currently playing
		String animationId = "";

		Session(UUID id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	/**
	 * One model on its way to one player. Only the hashes are queued; the encrypted
	 * bytes are loaded when the transfer reaches the front of the player's queue and
	 * dropped as soon as the last chunk is sent, so a player holds at most one model.
	 */
	static final class PendingTransfer {
		final ServerModel model;
		byte[] payload; // null until prepared
		boolean preparing;
		boolean failed;
		int offset;

		PendingTransfer(ServerModel model) {
			this.model = model;
		}
	}

	static final class UploadSession {
		long uploadId;
		UUID uploaderId;
		String modelId;
		String fileName;
		int totalBytes;
		String sha256;
		byte[] fileData;
		int bytesReceived;
		boolean failed;
		long lastActivityMs;
		long chunkWindowStartedMs;
		int chunksInWindow;
	}

	/**
	 * Catalog entry. Holds metadata only (a few KB): the model bytes stay on disk in
	 * {@link #file} and are read when a client asks for them.
	 */
	static final class ServerModel {
		String modelId;
		long hash1;
		long hash2;
		Path file;
		long size;
		String defaultTexture = "default";
		List<String> textureIds = List.of();
		List<String> extraAnimationIds = List.of();
		Map<String, List<String>> classifiedAnimationIds = Map.of();
	}

	private enum ImportKind { YSM, ZIP, SEVEN_ZIP, UNKNOWN }

	private final LunaBpmVelocity plugin;
	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;
	private final File modelsDir;
	private volatile BpmConfig config;

	private final ScheduledExecutorService net;
	private final ExecutorService workers;
	private final Semaphore modelProcessingPermits;
	private final SelectionStore selections;

	/** Encrypted payloads by hash, shared by every player downloading the same model at once. */
	private final Map<String, java.lang.ref.SoftReference<byte[]>> payloadCache = new ConcurrentHashMap<>();
	/** At most this many payloads are prepared (read + encrypted) at the same time. */
	private final Semaphore preparePermits = new Semaphore(2);
	/** At most this many players download a model at the same time; the rest wait their turn. */
	private static final int MAX_ACTIVE_DOWNLOADS = 6;
	private final Path cacheDir;

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	private final Map<Long, UploadSession> uploadSessions = new ConcurrentHashMap<>();
	private final Map<String, ServerModel> serverModels = new ConcurrentHashMap<>();
	private final Map<UUID, Set<UUID>> viewersByTarget = new ConcurrentHashMap<>();
	private final Map<UUID, Set<UUID>> targetsByViewer = new ConcurrentHashMap<>();
	private final Set<String> processingModelIds = ConcurrentHashMap.newKeySet();

	private long globalTokens = 0;
	private long globalBytesPerTick;
	private long playerBytesPerTick;
	private int roundRobinIndex = 0;
	private int lastActiveCount = 0;
	private volatile boolean catalogReady = false;

	public BpmService(LunaBpmVelocity plugin, ProxyServer proxy, Logger logger, Path dataDirectory, BpmConfig config) {
		this.plugin = plugin;
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
		this.config = config;
		this.modelsDir = dataDirectory.resolve("models").toFile();
		this.cacheDir = dataDirectory.resolve("cache");
		this.net = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "LunaBPM-net");
			thread.setDaemon(true);
			return thread;
		});
		int cores = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
		this.workers = Executors.newFixedThreadPool(cores, runnable -> {
			Thread thread = new Thread(runnable, "LunaBPM-worker");
			thread.setDaemon(true);
			return thread;
		});
		this.modelProcessingPermits = new Semaphore(cores);
		this.selections = new SelectionStore(dataDirectory.resolve("selections.json"), logger, workers);
		applyBandwidth();
	}

	// ------------------------------------------------------------------ lifecycle

	public void start() {
		modelsDir.mkdirs();
		loadServerKey();
		workers.execute(() -> {
			Map<String, ServerModel> catalog = buildModelCatalog();
			pruneCache(catalog.values());
			net.execute(() -> {
				serverModels.clear();
				serverModels.putAll(catalog);
				catalogReady = true;
				logger.info("Loaded {} model(s)", catalog.size());
			});
		});
		net.scheduleAtFixedRate(this::guardedTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
		// The proxy may have been restarted with players already on backends (it never
		// is, a proxy restart drops everyone) - but a plugin reload is cheap to support.
		for (Player player : proxy.getAllPlayers()) {
			onServerConnected(player);
		}
	}

	public void stop() {
		selections.save();
		net.shutdownNow();
		workers.shutdownNow();
	}

	private void applyBandwidth() {
		this.globalBytesPerTick = (config.globalBandwidthMbps * 1_000_000L) / 8L / 20L;
		this.playerBytesPerTick = (config.playerBandwidthMbps * 1_000_000L) / 8L / 20L;
	}

	private void loadServerKey() {
		Path keyFile = dataDirectory.resolve("server_key.dat");
		try {
			if (Files.exists(keyFile)) {
				byte[] key = Files.readAllBytes(keyFile);
				if (key.length == 56) {
					System.arraycopy(key, 0, SERVER_KEY, 0, 56);
					return;
				}
			}
			RANDOM.nextBytes(SERVER_KEY);
			Files.write(keyFile, SERVER_KEY);
			logger.info("Generated a new server_key.dat (clients will re-download models once)");
		} catch (Exception e) {
			logger.error("server_key.dat unusable, using a random key this run: {}", e.getMessage());
			RANDOM.nextBytes(SERVER_KEY);
		}
	}

	private static byte[] createClientKey() {
		byte[] key = new byte[56];
		new Random(114514).nextBytes(key);
		return key;
	}

	private void guardedTick() {
		try {
			processNetworkQueues();
		} catch (Throwable t) {
			logger.error("Network tick failed", t);
		}
	}

	private void post(Runnable task) {
		net.execute(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				logger.error("LunaBPM task failed", t);
			}
		});
	}

	private void later(long delayMs, Runnable task) {
		net.schedule(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				logger.error("LunaBPM task failed", t);
			}
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	public String statusLine() {
		long handshaken = sessions.values().stream().filter(s -> s.step >= 2).count();
		long bridged = sessions.values().stream().filter(s -> s.bridged).count();
		return "[BPM] " + serverModels.size() + " model, " + sessions.size() + " người online, "
			+ handshaken + " đã bắt tay, " + bridged + " có bridge, " + selections.size() + " lựa chọn đã lưu";
	}

	// ------------------------------------------------------------------ catalog

	private Map<String, ServerModel> buildModelCatalog() {
		Map<String, ServerModel> result = new LinkedHashMap<>();
		if (!modelsDir.exists()) {
			return result;
		}
		Path modelsPath = modelsDir.toPath();
		Path[] modelPaths;
		try (Stream<Path> walk = Files.walk(modelsPath)) {
			modelPaths = walk.filter(p -> {
				ImportKind kind = importKindFromFileName(p.getFileName().toString());
				return kind == ImportKind.YSM || kind == ImportKind.ZIP;
			}).sorted(Comparator.comparing(p -> modelsPath.relativize(p).toString(), String.CASE_INSENSITIVE_ORDER))
				.toArray(Path[]::new);
		} catch (Exception e) {
			logger.error("Failed to scan models: {}", e.getMessage());
			return result;
		}

		for (Path path : modelPaths) {
			try {
				if (!Files.isRegularFile(path)) {
					continue;
				}
				byte[] data = Files.readAllBytes(path);
				String relativePath = modelsPath.relativize(path).toString().replace('\\', '/');
				String modelId = normalizeModelId(relativePath);
				if (modelId == null) {
					logger.warn("Skipping model with invalid id: {}", relativePath);
					continue;
				}
				if (result.containsKey(modelId)) {
					// .ysm/.zip pairs and case aliases resolve to the same wire id
					logger.warn("Ignoring duplicate model id: {} ({})", modelId, relativePath);
					continue;
				}

				byte[] compiled = compileModelToYsm(data);
				if (compiled != null) {
					data = withYsmmPrefix(compiled);
					Path output = resolveModelOutput(modelId);
					writeAtomically(output, data);
					if (!output.equals(path)) {
						// keep the source next to its compiled form, renamed out of the scan
						Files.move(path, path.resolveSibling(path.getFileName() + ".source"), StandardCopyOption.REPLACE_EXISTING);
					}
					logger.info("Compiled model to YSMM format 32: {}", modelId);
				}

				Path stored = compiled != null ? resolveModelOutput(modelId) : path;
				result.put(modelId, createServerModel(modelId, data, stored));
			} catch (Exception e) {
				logger.warn("Failed to load model {}: {}", path.getFileName(), e.getMessage());
			}
		}
		return result;
	}

	public void reload(Consumer<String> onComplete) {
		config = BpmConfig.load(dataDirectory, logger);
		post(this::applyBandwidth);
		workers.execute(() -> {
			Map<String, ServerModel> fresh = buildModelCatalog();
			pruneCache(fresh.values());
			post(() -> {
				int previous = serverModels.size();
				serverModels.clear();
				serverModels.putAll(fresh);
				catalogReady = true;
				int resynced = 0;
				for (Session session : sessions.values()) {
					if (session.step < 2) {
						continue;
					}
					Optional<Player> player = proxy.getPlayer(session.id);
					if (player.isEmpty()) {
						continue;
					}
					try {
						sendCatalog(player.get(), session);
						resynced++;
					} catch (Exception e) {
						logger.warn("Failed to resend catalog to {}: {}", session.name, e.getMessage());
					}
				}
				String summary = "Reloaded " + serverModels.size() + " model(s) (was " + previous + "), re-synced " + resynced + " player(s).";
				logger.info(summary);
				if (onComplete != null) {
					onComplete.accept(summary);
				}
			});
		});
	}

	private static byte[] withYsmmPrefix(byte[] compiled) {
		byte[] data = new byte[compiled.length + 4];
		data[0] = 'Y';
		data[1] = 'S';
		data[2] = 'M';
		data[3] = 'M';
		System.arraycopy(compiled, 0, data, 4, compiled.length);
		return data;
	}

	private static boolean isYsmm(byte[] data) {
		return data.length >= 4 && data[0] == 'Y' && data[1] == 'S' && data[2] == 'M' && data[3] == 'M';
	}

	private byte[] compileModelToYsm(byte[] rawData) throws Exception {
		if (isYsmm(rawData)) {
			return null;
		}
		if (rawData.length >= 4 && rawData[0] == 0x50 && rawData[1] == 0x4B && rawData[2] == 0x03 && rawData[3] == 0x04) {
			Path tempFile = Files.createTempFile("ysm_upload_", ".zip");
			try {
				Files.write(tempFile, rawData);
				try (com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer deserializer = new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(tempFile)) {
					return compileRawModel(deserializer.deserialize());
				}
			} finally {
				Files.deleteIfExists(tempFile);
			}
		}

		int ysmCryptoVersion = rip.ysm.legacy.YesModelUtils.getYsmCryptoVersion(rawData);
		if (ysmCryptoVersion == 1 || ysmCryptoVersion == 2) {
			Map<String, byte[]> input = rip.ysm.legacy.YesModelUtils.input(rawData);
			try (com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer deserializer = new com.elfmcys.yesstevemodel.resource.YSMFolderDeserializer(input)) {
				return compileRawModel(deserializer.deserialize());
			}
		}

		// raw format-32 binary (a client upload without the YSMM prefix)
		if (rawData.length >= 4 && rawData[0] == 0x20 && rawData[1] == 0x00 && rawData[2] == 0x00 && rawData[3] == 0x00) {
			return Arrays.copyOf(rawData, rawData.length);
		}

		try {
			byte[] decrypted = YsmCrypt.decryptYsmFile(rawData);
			try (com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer deserializer = new com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer(decrypted)) {
				com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel rawModel = deserializer.deserializeKeepOpen();
				deserializer.parseYSMFooter(rawModel);
				return compileRawModel(rawModel);
			}
		} catch (Exception e) {
			throw new Exception("File is neither a ZIP archive, nor a legacy model, nor a valid encrypted Crypto V3 model.", e);
		}
	}

	private byte[] compileRawModel(com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel rawModel) throws Exception {
		try (YSMByteBuf serialized = com.elfmcys.yesstevemodel.resource.YSMBinarySerializer.serialize(rawModel, 32, true)) {
			ByteBuf raw = serialized.getRawBuf();
			byte[] bytes = new byte[raw.readableBytes()];
			raw.getBytes(raw.readerIndex(), bytes);
			return bytes;
		}
	}

	/** Builds the catalog entry from the model bytes; the bytes themselves are not kept. */
	private ServerModel createServerModel(String modelId, byte[] data, Path file) throws Exception {
		long[] hashes = YsmCrypt.calculateModelHashes(sha256Hex(data), SERVER_KEY);
		ServerModel model = new ServerModel();
		model.modelId = modelId;
		model.hash1 = hashes[0];
		model.hash2 = hashes[1];
		model.file = file;
		model.size = data.length;
		populateAnimationMetadata(model, data);
		return model;
	}

	private void populateAnimationMetadata(ServerModel model, byte[] data) {
		byte[] serialized = isYsmm(data) ? Arrays.copyOfRange(data, 4, data.length) : data;
		try (com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer deserializer =
				 new com.elfmcys.yesstevemodel.resource.YSMBinaryDeserializer(serialized, 32)) {
			com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel rawModel = deserializer.deserializeKeepOpen();
			model.defaultTexture = rawModel.properties.defaultTexture;
			model.textureIds = List.copyOf(rawModel.mainEntity.textures.keySet());
			model.extraAnimationIds = List.copyOf(rawModel.properties.extraAnimations.keySet());
			Map<String, List<String>> classified = new LinkedHashMap<>();
			for (com.elfmcys.yesstevemodel.resource.pojo.RawYsmModel.ExtraAnimationClassify classify : rawModel.properties.extraAnimationClassifies) {
				if (classify.id != null && !classify.id.isEmpty()) {
					classified.put(classify.id, List.copyOf(classify.extras.keySet()));
				}
			}
			model.classifiedAnimationIds = Map.copyOf(classified);
		} catch (Exception e) {
			logger.warn("Could not read animation metadata for {}: {}", model.modelId, e.getMessage());
		}
	}

	private static String sha256Hex(byte[] data) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
		StringBuilder hex = new StringBuilder(hash.length * 2);
		for (byte b : hash) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}

	// ------------------------------------------------------------------ connection events

	/** A player finished joining a backend (first join or a switch). */
	public void onServerConnected(Player player) {
		Optional<ServerConnection> current = player.getCurrentServer();
		if (current.isEmpty()) {
			return;
		}
		ServerConnection connection = current.get();
		String serverName = connection.getServerInfo().getName();

		post(() -> {
			Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session(id, player.getUsername()));
			clearTracking(session.id);
			session.step = 0;
			session.key1 = null;
			session.clientNextKey = null;
			session.clientKey = Arrays.copyOf(CLIENT_KEY, CLIENT_KEY.length);
			session.transferQueue.clear();
			session.currentTransfer = null;
			session.resyncAfterTransfers = false;
			session.resyncGeneration++;
			session.server = serverName;
			session.entityId = -1;
			session.world = "";
			session.bridged = false;
			session.animationId = "";
			long generation = ++session.connectGeneration;

			// Let the backend send us bridge messages for this player: Paper only delivers
			// a plugin message on a channel the "client" registered, and to a backend the
			// proxy is the client. Then ask the bridge for a fresh report. Sent after the
			// reset above so a quick answer can never be wiped by it.
			connection.sendPluginMessage(LunaBpmVelocity.REGISTER_CHANNEL, BridgeProtocol.CHANNEL.getBytes(StandardCharsets.UTF_8));
			connection.sendPluginMessage(LunaBpmVelocity.BRIDGE_CHANNEL, BridgeProtocol.request());

			// same delay PaperBPM used: give the client time to register the channel
			later(config.handshakeDelayMs, () -> {
				if (sessions.get(session.id) == session && session.connectGeneration == generation) {
					proxy.getPlayer(session.id).ifPresent(this::sendVersionCheck);
				}
			});
			// re-ask the bridge once in case the first request raced the backend's join
			later(config.handshakeDelayMs + 1000L, () -> {
				if (sessions.get(session.id) == session && session.connectGeneration == generation && !session.bridged) {
					requestBridgeState(session.id);
				}
			});
		});
	}

	public void onDisconnect(Player player) {
		UUID id = player.getUniqueId();
		post(() -> {
			clearTracking(id);
			sessions.remove(id);
			uploadSessions.entrySet().removeIf(entry -> entry.getValue().uploaderId.equals(id));
		});
	}

	private void requestBridgeState(UUID id) {
		proxy.getPlayer(id).flatMap(Player::getCurrentServer).ifPresent(connection -> {
			connection.sendPluginMessage(LunaBpmVelocity.REGISTER_CHANNEL, BridgeProtocol.CHANNEL.getBytes(StandardCharsets.UTF_8));
			connection.sendPluginMessage(LunaBpmVelocity.BRIDGE_CHANNEL, BridgeProtocol.request());
		});
	}

	// ------------------------------------------------------------------ bridge

	public void onBridgeMessage(Player carrier, String serverName, byte[] data) {
		post(() -> handleBridge(carrier, serverName, data));
	}

	private void handleBridge(Player carrier, String serverName, byte[] data) {
		if (data.length == 0) {
			return;
		}
		try (DataInputStream in = BridgeProtocol.reader(data)) {
			int type = in.readUnsignedByte();
			switch (type) {
				case BridgeProtocol.HELLO -> {
					int version = in.readInt();
					Session session = sessions.get(carrier.getUniqueId());
					if (session != null && serverName.equals(session.server)) {
						session.bridged = true;
					}
					if (version != BridgeProtocol.VERSION) {
						logger.warn("luna-bpm bridge on {} speaks protocol {}, expected {}", serverName, version, BridgeProtocol.VERSION);
					}
				}
				case BridgeProtocol.STATE -> {
					UUID subjectId = BridgeProtocol.readUuid(in);
					int entityId = in.readInt();
					String world = in.readUTF();
					Session subject = sessions.get(subjectId);
					if (subject == null || !serverName.equals(subject.server)) {
						return;
					}
					boolean changed = subject.entityId != entityId || !world.equals(subject.world);
					if (!world.equals(subject.world)) {
						clearTracking(subjectId);
					}
					subject.bridged = true;
					subject.entityId = entityId;
					subject.world = world;
					if (changed && subject.step >= 2) {
						proxy.getPlayer(subjectId).ifPresent(player -> {
							sendModelChange(subject, player);
							resyncPlayer(subject);
						});
					}
				}
				case BridgeProtocol.TRACK -> {
					UUID viewerId = BridgeProtocol.readUuid(in);
					UUID targetId = BridgeProtocol.readUuid(in);
					Session viewer = sessions.get(viewerId);
					Session target = sessions.get(targetId);
					if (viewer == null || target == null || viewerId.equals(targetId)
						|| !serverName.equals(viewer.server) || !serverName.equals(target.server)) {
						return;
					}
					viewersByTarget.computeIfAbsent(targetId, ignored -> ConcurrentHashMap.newKeySet()).add(viewerId);
					targetsByViewer.computeIfAbsent(viewerId, ignored -> ConcurrentHashMap.newKeySet()).add(targetId);
					if (target.step >= 2 && viewer.step >= 2) {
						proxy.getPlayer(viewerId).ifPresent(viewerPlayer -> sendModelChange(target, viewerPlayer));
					}
				}
				case BridgeProtocol.UNTRACK -> {
					UUID viewerId = BridgeProtocol.readUuid(in);
					UUID targetId = BridgeProtocol.readUuid(in);
					removeTracking(viewerId, targetId);
				}
				case BridgeProtocol.ENTITY_MODEL -> {
					int kind = in.readInt();
					UUID receiverId = BridgeProtocol.readUuid(in);
					UUID ownerId = BridgeProtocol.readUuid(in);
					int entityId = in.readInt();
					if (kind != BridgeProtocol.KIND_PROJECTILE && kind != BridgeProtocol.KIND_VEHICLE) {
						return;
					}
					Session receiver = sessions.get(receiverId);
					Session owner = sessions.get(ownerId);
					// like the original server mod, only owners running the mod lend their model
					if (receiver == null || owner == null || receiver.step < 2 || owner.step < 2
						|| !serverName.equals(receiver.server) || !serverName.equals(owner.server)) {
						return;
					}
					byte[] packet = entityModelPacket(kind, entityId, selections.get(ownerId).model());
					proxy.getPlayer(receiverId).ifPresent(player -> player.sendPluginMessage(LunaBpmVelocity.BPM_CHANNEL, packet));
				}
				default -> {
					// a newer bridge; ignore what we do not know
				}
			}
		} catch (Exception e) {
			logger.warn("Bad bridge message from {} via {}: {}", serverName, carrier.getUsername(), e.getMessage());
		}
	}

	// ------------------------------------------------------------------ client packets

	public void onClientPacket(Player player, byte[] data) {
		post(() -> handleClientPacket(player, data));
	}

	private void handleClientPacket(Player player, byte[] data) {
		if (data.length == 0) {
			return;
		}
		Session session = sessions.get(player.getUniqueId());
		if (session == null) {
			return;
		}

		ByteBuf buf = Unpooled.wrappedBuffer(data);
		int discriminator = buf.readUnsignedByte();
		try {
			if (discriminator == 52) { // C2SVersionCheckPacket
				String clientVersion = readString(buf);
				logger.info("Version check from {}: {}. Starting handshake.", player.getUsername(), clientVersion);
				initiateHandshake(player, session);
			} else if (discriminator == 2) { // C2SModelSyncPayload
				byte[] payload = new byte[buf.readableBytes()];
				buf.readBytes(payload);
				handleCryptoPayload(player, session, payload);
			} else if (session.step < 2) {
				return;
			} else if (discriminator == 5) {
				handleModelSwitch(player, session, buf);
			} else if (discriminator == 7) {
				handlePlayAnimation(player, session, buf);
			} else if (discriminator == 18) {
				handleAnimationExpression(session, buf);
			} else if (discriminator == 23) {
				handleSwingArm(player, buf);
			} else if (discriminator == 70) {
				handleUploadStart(player, session, buf);
			} else if (discriminator == 72) {
				handleUploadChunk(player, buf);
			} else if (discriminator == 73) {
				handleUploadFinish(player, buf);
			}
			// anything else is dropped, as in PaperBPM
		} catch (IndexOutOfBoundsException | IllegalStateException e) {
			logger.warn("Invalid packet {} from {}", discriminator, player.getUsername());
		} catch (Exception e) {
			logger.error("Error handling packet {} from {}: {}", discriminator, player.getUsername(), e.getMessage());
		}
	}

	private void sendVersionCheck(Player player) {
		ByteBuf buf = Unpooled.buffer();
		try {
			buf.writeByte(51); // S2CVersionCheckPacket
			writeString(buf, "2.6.0");
			writeString(buf, "open_ysm:v1");
			buf.writeBoolean(config.allowModelUpload);
			sendRaw(player, buf);
		} finally {
			buf.release();
		}
	}

	private void initiateHandshake(Player player, Session session) throws Exception {
		if (!catalogReady) {
			// the catalog is still compiling; ask again shortly instead of sending an empty list
			later(1000L, () -> {
				if (sessions.get(session.id) == session) {
					proxy.getPlayer(session.id).ifPresent(p -> {
						try {
							initiateHandshake(p, session);
						} catch (Exception e) {
							logger.warn("Deferred handshake for {} failed: {}", session.name, e.getMessage());
						}
					});
				}
			});
			return;
		}
		int garbageLen = 16 + RANDOM.nextInt(48);
		byte[] garbage = new byte[garbageLen];
		RANDOM.nextBytes(garbage);
		ByteBuf buf = Unpooled.buffer();
		try (YSMByteBuf out = new YSMByteBuf(buf)) {
			out.writeGarbageHeader(garbageLen, garbage);
			out.writeByte((byte) 0x01); // Packet 01: public key exchange
			YsmCrypt.EncryptedPacket result = YsmCrypt.encrypt(out.toArray(), YsmCrypt.publicKey, true);
			session.step = 1;
			session.key1 = result.nextKey();
			sendEncrypted(player, result.data());
		}
	}

	private void handleCryptoPayload(Player player, Session session, byte[] data) throws Exception {
		if (session.step == 1) {
			byte[] decrypted = YsmCrypt.decrypt(data, session.key1);
			if (decrypted == null || decrypted.length < 56) {
				return;
			}
			session.clientNextKey = Arrays.copyOfRange(decrypted, decrypted.length - 56, decrypted.length);
			byte[] payload = Arrays.copyOfRange(decrypted, 0, decrypted.length - 56);
			try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(payload))) {
				buf.skipGarbageHeader();
				if (buf.getRawBuf().readByte() != 0x02) {
					return;
				}
			}

			session.step = 2;
			sendCatalog(player, session);

			SelectionStore.Selection saved = selections.get(session.id);
			if (!"default".equals(saved.model())) {
				String resolved = resolveTextureOrDefault(serverModels.get(saved.model()), saved.texture());
				if (resolved == null) {
					selections.set(session.id, "default", "default");
				} else if (!resolved.equals(saved.texture())) {
					selections.set(session.id, saved.model(), resolved);
				}
			}

			sendModelChange(session, player);
			resyncPlayer(session);
			scheduleResyncs(session, 10, 40, 100);
			// the bridge's first report arrived before the client was ready, and entity
			// models (arrows, mounts) sent then were dropped; ask once more now
			requestBridgeState(session.id);
		} else if (session.step == 2 || session.step == 3) {
			byte[] decrypted = YsmCrypt.decrypt(data, session.key1);
			if (decrypted == null) {
				return;
			}
			try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
				buf.skipGarbageHeader();
				if (buf.getRawBuf().readByte() != 0x04) {
					return;
				}
				int count = buf.readVarInt();
				List<ServerModel> requested = new ArrayList<>();
				for (int i = 0; i < count; i++) {
					long h1 = readVarLong(buf.getRawBuf());
					long h2 = readVarLong(buf.getRawBuf());
					for (ServerModel model : serverModels.values()) {
						if (model.hash1 == h1 && model.hash2 == h2) {
							requested.add(model);
							break;
						}
					}
				}
				session.step = 3;
				if (!requested.isEmpty()) {
					logger.info("{} requested {} model(s)", player.getUsername(), requested.size());
				}
				queueModelTransfers(session, requested);
				session.resyncAfterTransfers = !requested.isEmpty();
				sendModelChange(session, player);
				resyncPlayer(session);
				if (requested.isEmpty()) {
					scheduleResyncs(session, 2, 20, 60);
				}
			}
		}
	}

	private void sendCatalog(Player player, Session session) throws Exception {
		int garbageLen = 16 + RANDOM.nextInt(48);
		byte[] garbage = new byte[garbageLen];
		RANDOM.nextBytes(garbage);
		ByteBuf buf = Unpooled.buffer();
		try (YSMByteBuf out = new YSMByteBuf(buf)) {
			out.writeGarbageHeader(garbageLen, garbage);
			out.writeVarInt(3); // Packet 03: full catalog
			out.writeVarLong(0L);
			out.getRawBuf().writeBytes(SERVER_KEY);
			out.getRawBuf().writeBytes(session.clientKey);
			out.writeVarInt(serverModels.size());
			for (ServerModel model : serverModels.values()) {
				writeVarLong(out.getRawBuf(), model.hash1);
				writeVarLong(out.getRawBuf(), model.hash2);
				out.writeString(model.modelId);
				out.writeVarInt(0); // isAuth
				out.writeVarInt(0); // isCustomSkinModel
				out.writeVarInt(1); // version
			}
			out.writeVarInt(0); // deleted models
			YsmCrypt.EncryptedPacket result = YsmCrypt.encrypt(out.toArray(), session.clientNextKey, false);
			sendEncrypted(player, result.data());
		}
	}

	private void broadcastPartialCatalog(ServerModel model) {
		for (Session receiver : sessions.values()) {
			if (receiver.step < 2) {
				continue;
			}
			Optional<Player> player = proxy.getPlayer(receiver.id);
			if (player.isEmpty()) {
				continue;
			}
			try {
				int garbageLen = 16 + RANDOM.nextInt(48);
				byte[] garbage = new byte[garbageLen];
				RANDOM.nextBytes(garbage);
				ByteBuf buf = Unpooled.buffer();
				try (YSMByteBuf out = new YSMByteBuf(buf)) {
					out.writeGarbageHeader(garbageLen, garbage);
					out.writeVarInt(3);
					out.writeVarLong(-1L); // partial
					out.getRawBuf().writeBytes(SERVER_KEY);
					out.getRawBuf().writeBytes(receiver.clientKey);
					out.writeVarInt(1);
					writeVarLong(out.getRawBuf(), model.hash1);
					writeVarLong(out.getRawBuf(), model.hash2);
					out.writeString(model.modelId);
					out.writeVarInt(0);
					out.writeVarInt(0);
					out.writeVarInt(1);
					out.writeVarInt(0);
					YsmCrypt.EncryptedPacket result = YsmCrypt.encrypt(out.toArray(), receiver.clientNextKey, false);
					sendEncrypted(player.get(), result.data());
				}
			} catch (Exception e) {
				logger.warn("Partial catalog to {} failed: {}", receiver.name, e.getMessage());
			}
		}
	}

	private void queueModelTransfers(Session session, List<ServerModel> requested) throws Exception {
		for (ServerModel model : requested) {
			session.transferQueue.add(new PendingTransfer(model));
		}
	}

	private void processNetworkQueues() {
		expireUploadSessions();
		globalTokens = Math.min(globalTokens + globalBytesPerTick, globalBytesPerTick * 20);

		// Who is downloading: players already mid-model first, then waiting players
		// while there is room. Each active player holds at most one model's bytes.
		int busy = 0;
		for (Session session : sessions.values()) {
			session.tokens = Math.min(session.tokens + playerBytesPerTick, playerBytesPerTick * 20);
			if (session.currentTransfer != null) {
				busy++;
			}
		}
		for (Session session : sessions.values()) {
			if (busy >= MAX_ACTIVE_DOWNLOADS) {
				break;
			}
			if (session.currentTransfer == null && !session.transferQueue.isEmpty()) {
				session.currentTransfer = session.transferQueue.poll();
				busy++;
			}
		}

		List<Session> active = new ArrayList<>();
		for (Session session : sessions.values()) {
			PendingTransfer transfer = session.currentTransfer;
			if (transfer == null) {
				continue;
			}
			if (transfer.failed) {
				logger.warn("Dropping model {} for {}: its payload could not be prepared", transfer.model.modelId, session.name);
				session.currentTransfer = null;
				continue;
			}
			if (transfer.payload == null) {
				if (!transfer.preparing) {
					preparePayload(transfer);
				}
				continue;
			}
			active.add(session);
		}
		if (active.isEmpty()) {
			return;
		}
		if (active.size() != lastActiveCount) {
			roundRobinIndex = 0;
			lastActiveCount = active.size();
		}

		boolean progress = true;
		while (globalTokens > 0 && progress) {
			progress = false;
			for (int i = 0; i < active.size(); i++) {
				roundRobinIndex = (roundRobinIndex + 1) % active.size();
				Session session = active.get(roundRobinIndex);
				Optional<Player> player = proxy.getPlayer(session.id);
				if (player.isEmpty()) {
					continue;
				}
				PendingTransfer transfer = session.currentTransfer;
				if (transfer == null || transfer.payload == null) {
					continue;
				}

				int remaining = transfer.payload.length - transfer.offset;
				int size = Math.min(32_000, remaining);
				if (size == 32_000 && session.tokens < size && session.tokens < playerBytesPerTick * 5) {
					continue;
				}
				if (size == 32_000 && globalTokens < size && globalTokens < globalBytesPerTick * 5) {
					continue;
				}

				try {
					int garbageLen = 16 + RANDOM.nextInt(48);
					byte[] garbage = new byte[garbageLen];
					RANDOM.nextBytes(garbage);
					ByteBuf buf = Unpooled.buffer();
					try (YSMByteBuf out = new YSMByteBuf(buf)) {
						out.writeGarbageHeader(garbageLen, garbage);
						out.writeVarInt(5); // Packet 05: model chunk
						writeVarLong(out.getRawBuf(), transfer.model.hash1);
						writeVarLong(out.getRawBuf(), transfer.model.hash2);
						out.writeVarInt(transfer.payload.length);
						out.writeVarInt(transfer.offset);
						out.writeVarInt(size);
						out.getRawBuf().writeBytes(transfer.payload, transfer.offset, size);
						YsmCrypt.EncryptedPacket result = YsmCrypt.encrypt(out.toArray(), session.key1, false);
						sendEncrypted(player.get(), result.data());
					}
					transfer.offset += size;
					session.tokens -= size;
					globalTokens -= size;
					progress = true;
					if (transfer.offset >= transfer.payload.length) {
						transfer.payload = null; // let the bytes go now
						session.currentTransfer = null;
						if (session.transferQueue.isEmpty() && session.resyncAfterTransfers) {
							session.resyncAfterTransfers = false;
							scheduleResyncs(session, 1, 20, 60);
						}
					}
				} catch (Exception e) {
					logger.warn("Model chunk to {} failed: {}", session.name, e.getMessage());
					session.currentTransfer = null;
				}
			}
		}
	}

	/** Load (or encrypt and cache) a transfer's payload off the network thread. */
	private void preparePayload(PendingTransfer transfer) {
		if (!preparePermits.tryAcquire()) {
			return; // try again next tick
		}
		transfer.preparing = true;
		workers.execute(() -> {
			byte[] payload = null;
			try {
				payload = loadPayload(transfer.model);
			} catch (Throwable t) {
				logger.warn("Could not prepare model {}: {}", transfer.model.modelId, t.getMessage());
			} finally {
				preparePermits.release();
			}
			byte[] ready = payload;
			post(() -> {
				transfer.preparing = false;
				if (ready == null) {
					transfer.failed = true;
				} else {
					transfer.payload = ready;
				}
			});
		});
	}

	private static String cacheKey(ServerModel model) {
		return Long.toUnsignedString(model.hash1, 16) + "_" + Long.toUnsignedString(model.hash2, 16);
	}

	/**
	 * The encrypted, zstd-compressed form clients download. Encrypting is the costly part,
	 * so the result is kept on disk under cache/ (the hashes already depend on the server
	 * key and the model bytes, so a cached file can never be stale) and, while anyone is
	 * using it, softly in memory so concurrent downloads of one model share one array.
	 */
	private byte[] loadPayload(ServerModel model) throws Exception {
		String key = cacheKey(model);
		java.lang.ref.SoftReference<byte[]> ref = payloadCache.get(key);
		byte[] payload = ref == null ? null : ref.get();
		if (payload != null) {
			return payload;
		}
		Path cached = cacheDir.resolve(key + ".bin");
		if (Files.isRegularFile(cached)) {
			payload = Files.readAllBytes(cached);
		} else {
			byte[] data = Files.readAllBytes(model.file);
			int offset = isYsmm(data) ? 4 : 0;
			payload = YsmCrypt.encryptServerCache(data, offset, data.length - offset, SERVER_KEY, model.hash1, model.hash2);
			data = null;
			try {
				writeAtomically(cached, payload);
			} catch (Exception e) {
				logger.warn("Could not cache payload of {}: {}", model.modelId, e.getMessage());
			}
		}
		payloadCache.put(key, new java.lang.ref.SoftReference<>(payload));
		return payload;
	}

	/** Remove cached payloads of models no longer in the catalog. */
	private void pruneCache(java.util.Collection<ServerModel> catalog) {
		Set<String> keep = new HashSet<>();
		for (ServerModel model : catalog) {
			keep.add(cacheKey(model) + ".bin");
		}
		payloadCache.keySet().removeIf(key -> !keep.contains(key + ".bin"));
		if (!Files.isDirectory(cacheDir)) {
			return;
		}
		try (Stream<Path> files = Files.list(cacheDir)) {
			files.filter(f -> !keep.contains(f.getFileName().toString())).forEach(f -> {
				try {
					Files.deleteIfExists(f);
				} catch (Exception ignored) {
					// next prune will retry
				}
			});
		} catch (Exception e) {
			logger.warn("Could not prune the payload cache: {}", e.getMessage());
		}
	}

	// ------------------------------------------------------------------ model state

	private void handleModelSwitch(Player player, Session session, ByteBuf buf) {
		String modelId = readString(buf);
		String textureId = readString(buf);
		if (modelId.isEmpty() || modelId.length() > MAX_MODEL_ID_LENGTH || textureId.length() > MAX_MODEL_ID_LENGTH) {
			return;
		}
		if (!"default".equals(modelId) && !serverModels.containsKey(modelId)) {
			logger.warn("{} requested an unknown model: {}", player.getUsername(), modelId);
			setChoice(session, "default", "default");
		} else if (config.requireModelPermission && !"default".equals(modelId)
			&& !player.hasPermission("bpm.model." + modelId.toLowerCase(Locale.ROOT))) {
			logger.warn("{} attempted to use an unpermitted model: {}", player.getUsername(), modelId);
			setChoice(session, "default", "default");
		} else {
			String resolved = "default".equals(modelId) ? "default" : resolveTextureOrDefault(serverModels.get(modelId), textureId);
			if (resolved == null) {
				setChoice(session, "default", "default");
			} else {
				setChoice(session, modelId, resolved);
			}
		}
		sendModelChange(session, player);
		broadcastModelChange(session, null);
		player.getCurrentServer().ifPresent(connection ->
			connection.sendPluginMessage(LunaBpmVelocity.BRIDGE_CHANNEL, BridgeProtocol.refreshVehicle()));
	}

	private void setChoice(Session session, String modelId, String textureId) {
		selections.set(session.id, modelId, textureId);
		session.animationId = "";
	}

	private String resolveTextureOrDefault(ServerModel model, String requested) {
		if (model == null || model.textureIds.isEmpty()) {
			return null;
		}
		String normalized = normalizeTextureId(requested);
		if (normalized != null && model.textureIds.contains(normalized)) {
			return normalized;
		}
		String fallback = normalizeTextureId(model.defaultTexture);
		if (fallback != null && model.textureIds.contains(fallback)) {
			return fallback;
		}
		return model.textureIds.get(0);
	}

	private static String normalizeTextureId(String textureId) {
		if (textureId == null) {
			return null;
		}
		if (textureId.toLowerCase(Locale.ROOT).endsWith(".png") && textureId.length() > 4) {
			return textureId.substring(0, textureId.length() - 4);
		}
		return textureId;
	}

	private void handlePlayAnimation(Player player, Session session, ByteBuf buf) {
		int animationIndex = readVarInt(buf);
		String category = readString(buf);
		int entityId = buf.isReadable() ? readVarInt(buf) : -1;
		// only the player's own model is emulated (no modded entities behind a proxy)
		if (entityId != -1 && entityId != session.entityId) {
			return;
		}
		String animationId = resolveAnimationId(selections.get(session.id).model(), category, animationIndex);
		if (animationId == null) {
			return;
		}
		session.animationId = animationId;
		broadcastPlayerState(session, animationId);
	}

	private String resolveAnimationId(String modelId, String category, int index) {
		if (index == -1) {
			return "";
		}
		if (index < 0) {
			return null;
		}
		ServerModel model = serverModels.get(modelId);
		if (model == null) {
			return null;
		}
		List<String> ids = category.isEmpty() ? model.extraAnimationIds : model.classifiedAnimationIds.get(category);
		if (ids == null || index >= ids.size()) {
			return null;
		}
		return ids.get(index);
	}

	private void handleAnimationExpression(Session session, ByteBuf buf) {
		int count = buf.readUnsignedByte();
		if (count > MAX_EXPRESSION_VALUES || buf.readableBytes() < count * Float.BYTES || session.entityId == -1) {
			return;
		}
		float[] values = new float[count];
		for (int i = 0; i < count; i++) {
			values[i] = buf.readFloat();
		}
		ByteBuf out = Unpooled.buffer(8 + count * Float.BYTES);
		try {
			out.writeByte(19); // S2CSyncAnimationExpressionPacket
			writeVarInt(out, session.entityId);
			out.writeByte(count);
			for (float value : values) {
				out.writeFloat(value);
			}
			broadcastToViewers(session, copyReadableBytes(out), true);
		} finally {
			out.release();
		}
	}

	private void handleSwingArm(Player player, ByteBuf buf) {
		int hand = readVarInt(buf);
		if (hand == 0 || hand == 1) {
			player.getCurrentServer().ifPresent(connection ->
				connection.sendPluginMessage(LunaBpmVelocity.BRIDGE_CHANNEL, BridgeProtocol.swing(hand)));
		}
	}

	private void broadcastPlayerState(Session subject, String animationId) {
		if (subject.entityId == -1) {
			return;
		}
		ByteBuf out = Unpooled.buffer();
		try {
			out.writeByte(21); // S2CSyncPlayerStatePacket
			writeVarInt(out, subject.entityId);
			out.writeShort(MODEL_SWITCH_FLAG);
			writeString(out, animationId);
			broadcastToViewers(subject, copyReadableBytes(out), true);
		} finally {
			out.release();
		}
	}

	/** Whether {@code receiver} should hear about {@code subject}: same backend and world, both ready. */
	private boolean canSee(Session receiver, Session subject) {
		return receiver != null && receiver.step >= 2
			&& subject.entityId != -1
			&& receiver.server.equals(subject.server)
			&& receiver.world.equals(subject.world);
	}

	private void broadcastToViewers(Session subject, byte[] data, boolean includeSubject) {
		Set<UUID> receivers = new HashSet<>(viewersByTarget.getOrDefault(subject.id, Set.of()));
		if (includeSubject) {
			receivers.add(subject.id);
		}
		for (UUID receiverId : receivers) {
			Session receiver = sessions.get(receiverId);
			if (canSee(receiver, subject)) {
				proxy.getPlayer(receiverId).ifPresent(player -> player.sendPluginMessage(LunaBpmVelocity.BPM_CHANNEL, data));
			}
		}
	}

	/** S2CSetModelAndTexturePacket about {@code subject}, to one receiver. */
	private void sendModelChange(Session subject, Player receiver) {
		Session receiverSession = sessions.get(receiver.getUniqueId());
		if (!canSee(receiverSession, subject)) {
			return;
		}
		receiver.sendPluginMessage(LunaBpmVelocity.BPM_CHANNEL, modelChangePacket(subject));
	}

	/** S2CSetModelAndTexturePacket about {@code subject}, to everyone tracking it. */
	private void broadcastModelChange(Session subject, Player ignored) {
		if (subject.entityId == -1) {
			return;
		}
		broadcastToViewers(subject, modelChangePacket(subject), false);
	}

	private byte[] modelChangePacket(Session subject) {
		SelectionStore.Selection selection = selections.get(subject.id);
		ByteBuf out = Unpooled.buffer();
		try {
			out.writeByte(4); // S2CSetModelAndTexturePacket
			writeVarInt(out, subject.entityId);
			writeString(out, selection.model());
			writeString(out, selection.texture());
			out.writeBoolean(false); // disabled
			// embedded S2CSyncPlayerStatePacket
			writeVarInt(out, subject.entityId);
			out.writeShort(MODEL_SWITCH_FLAG);
			writeString(out, subject.animationId);
			return copyReadableBytes(out);
		} finally {
			out.release();
		}
	}

	/**
	 * S2CSyncProjectileModelPacket (16) or S2CSyncVehicleModelPacket (22): entity id, then
	 * the capability as network NBT (an unnamed root compound) holding owner_model_id,
	 * initialized and an empty molang_vars_server_bound.
	 */
	private static byte[] entityModelPacket(int kind, int entityId, String ownerModelId) {
		ByteBuf out = Unpooled.buffer();
		try {
			out.writeByte(kind);
			writeVarInt(out, entityId);
			out.writeBytes(ownerModelNbt(ownerModelId));
			return copyReadableBytes(out);
		} finally {
			out.release();
		}
	}

	private static byte[] ownerModelNbt(String ownerModelId) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
		try (DataOutputStream nbt = new DataOutputStream(bytes)) {
			nbt.writeByte(10); // TAG_Compound, unnamed root (network NBT since 1.20.2)
			nbt.writeByte(8); // TAG_String
			nbt.writeUTF("owner_model_id");
			nbt.writeUTF(ownerModelId);
			nbt.writeByte(1); // TAG_Byte
			nbt.writeUTF("initialized");
			nbt.writeByte(1);
			nbt.writeByte(10); // TAG_Compound
			nbt.writeUTF("molang_vars_server_bound");
			nbt.writeByte(0); // its TAG_End
			nbt.writeByte(0); // root TAG_End
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		return bytes.toByteArray();
	}

	/** Tell the player's viewers about them, and them about everyone they track. */
	private void resyncPlayer(Session session) {
		if (session.step < 2) {
			return;
		}
		for (UUID viewerId : viewersByTarget.getOrDefault(session.id, Set.of())) {
			proxy.getPlayer(viewerId).ifPresent(viewer -> sendModelChange(session, viewer));
		}
		Optional<Player> self = proxy.getPlayer(session.id);
		if (self.isEmpty()) {
			return;
		}
		for (UUID targetId : targetsByViewer.getOrDefault(session.id, Set.of())) {
			Session target = sessions.get(targetId);
			if (target != null && target.step >= 2) {
				sendModelChange(target, self.get());
			}
		}
	}

	/**
	 * A few bounded resends: the client decodes model payloads on a worker thread
	 * while state packets apply on the game thread (same reasoning as PaperBPM).
	 */
	private void scheduleResyncs(Session session, long... ticks) {
		long generation = ++session.resyncGeneration;
		long connect = session.connectGeneration;
		for (long tick : ticks) {
			later(Math.max(1L, tick) * TICK_MS, () -> {
				if (sessions.get(session.id) != session || session.step < 2
					|| session.resyncGeneration != generation || session.connectGeneration != connect) {
					return;
				}
				proxy.getPlayer(session.id).ifPresent(player -> sendModelChange(session, player));
				resyncPlayer(session);
			});
		}
	}

	private void clearTracking(UUID id) {
		Set<UUID> viewers = viewersByTarget.remove(id);
		if (viewers != null) {
			for (UUID viewerId : viewers) {
				Set<UUID> targets = targetsByViewer.get(viewerId);
				if (targets != null) {
					targets.remove(id);
					if (targets.isEmpty()) {
						targetsByViewer.remove(viewerId, targets);
					}
				}
			}
		}
		Set<UUID> targets = targetsByViewer.remove(id);
		if (targets != null) {
			for (UUID targetId : targets) {
				Set<UUID> byTarget = viewersByTarget.get(targetId);
				if (byTarget != null) {
					byTarget.remove(id);
					if (byTarget.isEmpty()) {
						viewersByTarget.remove(targetId, byTarget);
					}
				}
			}
		}
	}

	private void removeTracking(UUID viewerId, UUID targetId) {
		Set<UUID> viewers = viewersByTarget.get(targetId);
		if (viewers != null) {
			viewers.remove(viewerId);
			if (viewers.isEmpty()) {
				viewersByTarget.remove(targetId, viewers);
			}
		}
		Set<UUID> targets = targetsByViewer.get(viewerId);
		if (targets != null) {
			targets.remove(targetId);
			if (targets.isEmpty()) {
				targetsByViewer.remove(viewerId, targets);
			}
		}
	}

	// ------------------------------------------------------------------ uploads

	private void handleUploadStart(Player player, Session session, ByteBuf buf) {
		String requestedModelId = readString(buf);
		String fileName = readString(buf);
		int totalBytes = readVarInt(buf);
		String sha256 = readString(buf);
		int maxTotalBytes = uploadMaxBytes();

		if (!config.allowModelUpload) {
			sendUploadStartResult(player, 0L, (byte) 6, maxTotalBytes, "Model import disabled.");
			return;
		}
		String modelId = normalizeModelId(requestedModelId);
		ImportKind kind = importKindFromFileName(fileName);
		if (requestedModelId.length() > MAX_MODEL_ID_LENGTH || modelId == null || fileName.length() > MAX_FILE_NAME_LENGTH
			|| kind == ImportKind.UNKNOWN || !isSha256(sha256)) {
			sendUploadStartResult(player, 0L, (byte) 5, maxTotalBytes, "Invalid model id or hash.");
			return;
		}
		if (kind == ImportKind.SEVEN_ZIP) {
			sendUploadStartResult(player, 0L, (byte) 7, maxTotalBytes, "7z import is not supported yet.");
			return;
		}
		if (totalBytes <= 0 || totalBytes > maxTotalBytes) {
			sendUploadStartResult(player, 0L, (byte) 2, maxTotalBytes, "File exceeds server limit.");
			return;
		}
		if (serverModels.containsKey(modelId) || processingModelIds.contains(modelId)
			|| uploadSessions.values().stream().anyMatch(s -> s.modelId.equals(modelId))) {
			sendUploadStartResult(player, 0L, (byte) 1, maxTotalBytes, "Model ID already exists.");
			return;
		}
		long mine = uploadSessions.values().stream().filter(s -> s.uploaderId.equals(session.id)).count();
		long reserved = uploadSessions.values().stream().mapToLong(s -> s.totalBytes).sum();
		if (mine >= MAX_ACTIVE_UPLOADS_PER_PLAYER || uploadSessions.size() >= MAX_ACTIVE_UPLOAD_SESSIONS
			|| reserved + totalBytes > Math.max(maxTotalBytes, Math.min(MAX_CLIENT_UPLOAD_BYTES, (long) maxTotalBytes * 2))) {
			sendUploadStartResult(player, 0L, (byte) 8, maxTotalBytes, "Upload memory budget exceeded.");
			return;
		}

		long uploadId;
		do {
			uploadId = RANDOM.nextLong();
		} while (uploadId == 0L || uploadSessions.containsKey(uploadId));
		UploadSession upload = new UploadSession();
		upload.uploadId = uploadId;
		upload.uploaderId = session.id;
		upload.modelId = modelId;
		upload.fileName = fileName;
		upload.totalBytes = totalBytes;
		upload.sha256 = sha256.toLowerCase(Locale.ROOT);
		upload.fileData = new byte[totalBytes];
		upload.lastActivityMs = System.currentTimeMillis();
		uploadSessions.put(uploadId, upload);
		logger.info("Accepting model upload from {} for {} ({} bytes)", player.getUsername(), modelId, totalBytes);
		sendUploadStartResult(player, uploadId, (byte) 0, maxTotalBytes, "");
	}

	private void handleUploadChunk(Player player, ByteBuf buf) {
		long uploadId = readVarLong(buf);
		UploadSession upload = uploadSessions.get(uploadId);
		if (upload == null || !upload.uploaderId.equals(player.getUniqueId())) {
			return;
		}
		upload.lastActivityMs = System.currentTimeMillis();
		int offset = readVarInt(buf);
		int length = readVarInt(buf);
		if (length < 1 || length > UPLOAD_CHUNK_SIZE || length > buf.readableBytes()
			|| offset < 0 || (long) offset + length > upload.totalBytes || offset != upload.bytesReceived) {
			upload.failed = true;
			return;
		}
		long now = System.currentTimeMillis();
		if (now - upload.chunkWindowStartedMs >= UPLOAD_RATE_WINDOW_MS) {
			upload.chunkWindowStartedMs = now;
			upload.chunksInWindow = 0;
		}
		int perSecond = config.modelUploadChunksPerTick * 20;
		if (upload.chunksInWindow >= perSecond + Math.max(400, perSecond * 10)) {
			upload.failed = true;
			return;
		}
		upload.chunksInWindow++;
		buf.readBytes(upload.fileData, offset, length);
		upload.bytesReceived += length;
	}

	private void handleUploadFinish(Player player, ByteBuf buf) throws Exception {
		long uploadId = readVarLong(buf);
		UploadSession upload = uploadSessions.remove(uploadId);
		if (upload == null || !upload.uploaderId.equals(player.getUniqueId())) {
			return;
		}
		if (upload.failed || upload.bytesReceived != upload.totalBytes) {
			sendUploadResult(player, uploadId, (byte) 5, "", 0L, 0L, "Incomplete upload.");
			return;
		}
		if (!sha256Hex(upload.fileData).equals(upload.sha256)) {
			sendUploadResult(player, uploadId, (byte) 1, "", 0L, 0L, "SHA-256 verification failed.");
			return;
		}
		if (!processingModelIds.add(upload.modelId)) {
			sendUploadResult(player, uploadId, (byte) 1, "", 0L, 0L, "Model ID already exists.");
			return;
		}
		if (!modelProcessingPermits.tryAcquire()) {
			processingModelIds.remove(upload.modelId);
			sendUploadResult(player, uploadId, (byte) 8, "", 0L, 0L, "Server is processing too many model imports.");
			return;
		}
		logger.info("Completed model upload from {} for {}; compiling", player.getUsername(), upload.modelId);
		UUID uploaderId = player.getUniqueId();
		workers.execute(() -> {
			ServerModel model = null;
			String error = null;
			try {
				byte[] data = upload.fileData;
				byte[] compiled = compileModelToYsm(data);
				if (compiled != null) {
					data = withYsmmPrefix(compiled);
				}
				Path output = resolveModelOutput(upload.modelId);
				writeAtomically(output, data);
				model = createServerModel(upload.modelId, data, output);
			} catch (Exception e) {
				logger.warn("Failed to process uploaded model {}: {}", upload.modelId, e.getMessage());
				error = "The uploaded model could not be processed.";
			}
			ServerModel done = model;
			String failure = error;
			post(() -> {
				try {
					Optional<Player> uploader = proxy.getPlayer(uploaderId);
					if (done != null) {
						serverModels.put(done.modelId, done);
						broadcastPartialCatalog(done);
						uploader.ifPresent(p -> sendUploadResult(p, upload.uploadId, (byte) 0, done.modelId, done.hash1, done.hash2, ""));
					} else {
						uploader.ifPresent(p -> sendUploadResult(p, upload.uploadId, (byte) 2, "", 0L, 0L, failure));
					}
				} finally {
					processingModelIds.remove(upload.modelId);
					modelProcessingPermits.release();
				}
			});
		});
	}

	private void sendUploadStartResult(Player player, long uploadId, byte status, int maxTotalBytes, String message) {
		ByteBuf out = Unpooled.buffer();
		try {
			out.writeByte(71);
			writeVarLong(out, uploadId);
			out.writeByte(status);
			writeVarInt(out, status == 0 ? UPLOAD_CHUNK_SIZE : 0);
			writeVarInt(out, status == 0 ? maxTotalBytes : 0);
			writeVarInt(out, status == 0 ? config.modelUploadChunksPerTick : 0);
			writeString(out, message);
			player.sendPluginMessage(LunaBpmVelocity.BPM_CHANNEL, copyReadableBytes(out));
		} finally {
			out.release();
		}
	}

	private void sendUploadResult(Player player, long uploadId, byte status, String modelId, long hash1, long hash2, String message) {
		ByteBuf out = Unpooled.buffer();
		try {
			out.writeByte(74);
			writeVarLong(out, uploadId);
			out.writeByte(status);
			writeString(out, modelId);
			writeVarLong(out, hash1);
			writeVarLong(out, hash2);
			writeString(out, message);
			player.sendPluginMessage(LunaBpmVelocity.BPM_CHANNEL, copyReadableBytes(out));
		} finally {
			out.release();
		}
	}

	private int uploadMaxBytes() {
		int mib = Math.max(1, Math.min(config.modelUploadMaxMiB, MAX_CLIENT_UPLOAD_BYTES / (1024 * 1024)));
		return mib * 1024 * 1024;
	}

	private void expireUploadSessions() {
		long now = System.currentTimeMillis();
		uploadSessions.entrySet().removeIf(entry -> now - entry.getValue().lastActivityMs > UPLOAD_SESSION_TIMEOUT_MS);
	}

	// ------------------------------------------------------------------ files and ids

	private String normalizeModelId(String modelId) {
		if (modelId == null) {
			return null;
		}
		String normalized = modelId.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		boolean stripped;
		do {
			stripped = false;
			for (String extension : new String[]{".ysm", ".zip", ".7z"}) {
				if (normalized.endsWith(extension)) {
					normalized = normalized.substring(0, normalized.length() - extension.length());
					stripped = true;
				}
			}
		} while (stripped);
		normalized = normalized.replaceAll("/+", "/");
		if (normalized.isBlank() || normalized.contains("..") || normalized.length() > MAX_MODEL_ID_LENGTH) {
			return null;
		}
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if (Character.isISOControl(c) || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
				return null;
			}
		}
		return normalized;
	}

	private static ImportKind importKindFromFileName(String fileName) {
		if (fileName == null) {
			return ImportKind.UNKNOWN;
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".ysm")) {
			return ImportKind.YSM;
		}
		if (lower.endsWith(".zip")) {
			return ImportKind.ZIP;
		}
		if (lower.endsWith(".7z")) {
			return ImportKind.SEVEN_ZIP;
		}
		return ImportKind.UNKNOWN;
	}

	private static boolean isSha256(String value) {
		if (value.length() != 64) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (Character.digit(value.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}

	private Path resolveModelOutput(String modelId) throws java.io.IOException {
		Path root = modelsDir.toPath().toAbsolutePath().normalize();
		Path output = root.resolve(modelId + ".ysm").normalize();
		if (!output.startsWith(root)) {
			throw new java.io.IOException("Model path escapes the models directory.");
		}
		return output;
	}

	private static void writeAtomically(Path output, byte[] data) throws java.io.IOException {
		Files.createDirectories(output.getParent());
		Path temp = Files.createTempFile(output.getParent(), output.getFileName().toString(), ".tmp");
		try {
			Files.write(temp, data);
			try {
				Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	// ------------------------------------------------------------------ wire helpers

	private void sendEncrypted(Player player, byte[] encrypted) {
		ByteBuf buf = Unpooled.buffer(encrypted.length + 1);
		try {
			buf.writeByte(1); // S2CModelSyncPayload
			buf.writeBytes(encrypted);
			sendRaw(player, buf);
		} finally {
			buf.release();
		}
	}

	private static void sendRaw(Player player, ByteBuf buf) {
		byte[] data = new byte[buf.readableBytes()];
		buf.readBytes(data);
		player.sendPluginMessage(LunaBpmVelocity.BPM_CHANNEL, data);
	}

	private static byte[] copyReadableBytes(ByteBuf buf) {
		byte[] data = new byte[buf.readableBytes()];
		buf.getBytes(buf.readerIndex(), data);
		return data;
	}

	private static void writeString(ByteBuf buf, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeVarInt(buf, bytes.length);
		buf.writeBytes(bytes);
	}

	private static String readString(ByteBuf buf) {
		int length = readVarInt(buf);
		if (length < 0 || length > 32767) {
			throw new IllegalStateException("String length out of bounds: " + length);
		}
		byte[] bytes = new byte[length];
		buf.readBytes(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void writeVarInt(ByteBuf buf, int value) {
		while ((value & -128) != 0) {
			buf.writeByte(value & 127 | 128);
			value >>>= 7;
		}
		buf.writeByte(value);
	}

	private static int readVarInt(ByteBuf buf) {
		int value = 0;
		int position = 0;
		while (true) {
			byte current = buf.readByte();
			value |= (current & 127) << position;
			if ((current & 128) == 0) {
				return value;
			}
			position += 7;
			if (position >= 32) {
				throw new IllegalStateException("VarInt is too big");
			}
		}
	}

	private static void writeVarLong(ByteBuf buf, long value) {
		while ((value & -128L) != 0L) {
			buf.writeByte((int) (value & 127L) | 128);
			value >>>= 7;
		}
		buf.writeByte((int) value);
	}

	private static long readVarLong(ByteBuf buf) {
		long value = 0L;
		int position = 0;
		while (true) {
			byte current = buf.readByte();
			value |= (long) (current & 127) << position;
			if ((current & 128) == 0) {
				return value;
			}
			position += 7;
			if (position >= 64) {
				throw new IllegalStateException("VarLong is too big");
			}
		}
	}
}
