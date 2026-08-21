package dev.belikhun.luna.tv.audio;

import java.util.function.Consumer;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

import dev.belikhun.luna.core.api.logging.LunaLogger;

/**
 * Luna TV's registration with Simple Voice Chat.
 *
 * The server API only exists while the voice server is up, and it arrives on an
 * event rather than being fetchable, so this holds it and tells the audio
 * subsystem when it appears and disappears. A screen asked to make noise before
 * that happens is not an error, it just has nowhere to send audio yet.
 */
public final class LunaTvVoicechatPlugin implements VoicechatPlugin {

	/** Volume category id; also the client's slider label group. */
	public static final String CATEGORY_ID = "lunatv";

	private final LunaLogger logger;
	private final Consumer<VoicechatServerApi> onStarted;
	private final Runnable onStopped;

	private volatile VoicechatServerApi api;

	public LunaTvVoicechatPlugin(
		LunaLogger logger,
		Consumer<VoicechatServerApi> onStarted,
		Runnable onStopped
	) {
		this.logger = logger;
		this.onStarted = onStarted;
		this.onStopped = onStopped;
	}

	@Override
	public String getPluginId() {
		return CATEGORY_ID;
	}

	@Override
	public void initialize(VoicechatApi voicechatApi) {
		// nothing to do: the server API is what this plugin needs, and it only
		// exists once the voice server has started
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
		registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
	}

	private void onServerStarted(VoicechatServerStartedEvent event) {
		api = event.getVoicechat();

		registerCategory(api);
		logger.audit("Đã kết nối Luna TV với Simple Voice Chat.");
		onStarted.accept(api);
	}

	private void onServerStopped(VoicechatServerStoppedEvent event) {
		api = null;
		onStopped.run();
	}

	private void registerCategory(VoicechatServerApi serverApi) {
		try {
			VolumeCategory category = serverApi.volumeCategoryBuilder()
				.setId(CATEGORY_ID)
				.setName("Luna TV")
				.setDescription("Âm thanh từ màn hình Luna TV")
				.setIcon(icon())
				.build();

			serverApi.registerVolumeCategory(category);
		} catch (Throwable throwable) {
			logger.warn("Không đăng ký được nhóm âm lượng Luna TV: " + throwable);
		}
	}

	/**
	 * The 16x16 ARGB icon voice chat shows beside the volume slider.
	 *
	 * Drawn in code rather than shipped as a PNG: the API wants an int matrix,
	 * and a screen outline is a few rectangles.
	 *
	 * @return a 16x16 matrix of ARGB pixels
	 */
	private static int[][] icon() {
		int[][] pixels = new int[16][16];
		int frame = 0xFFE5E7EB;
		int glass = 0xFF3B82F6;

		for (int y = 2; y <= 11; y++) {
			for (int x = 1; x <= 14; x++) {
				boolean border = y == 2 || y == 11 || x == 1 || x == 14;
				pixels[y][x] = border ? frame : glass;
			}
		}

		// the stand
		for (int x = 6; x <= 9; x++) {
			pixels[12][x] = frame;
		}

		for (int x = 4; x <= 11; x++) {
			pixels[13][x] = frame;
		}

		return pixels;
	}

	/** The live server API, or null when the voice server is not running. */
	public VoicechatServerApi api() {
		return api;
	}
}
