package dev.belikhun.luna.tv.client.input;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.belikhun.luna.tv.client.net.TvPayload;
import dev.belikhun.luna.tv.client.render.ScreenQuad;

/**
 * Turns looking, clicking and typing into browser input.
 *
 * The pointer is the crosshair: a player aims at the wall and the point they are
 * looking at is where the cursor goes. That is the only scheme that works without
 * a mouse cursor to lend, and it is what the map version already did, so the two
 * audiences point at the same place.
 *
 * Input is taken at the GLFW callbacks rather than through Minecraft's own
 * handlers or a keybind. Two reasons: the callbacks are the one input API both
 * game lines spell identically, so this file is shared rather than written twice;
 * and chaining them is what lets a click be swallowed. A click that reached both
 * the browser and the game would swing the player's arm at the wall behind the
 * screen every time somebody pressed a button on a web page.
 */
public final class ScreenInput {

	/** Must match ClientLink's constants on the server. */
	private static final byte POINTER_DOWN = 1;
	private static final byte POINTER_UP = 2;
	private static final byte POINTER_MOVE = 3;
	private static final byte SCROLL = 4;
	private static final byte KEY = 5;
	private static final byte TEXT = 6;

	/** CDP's modifier mask, which is not GLFW's. */
	private static final int MOD_ALT = 1;
	private static final int MOD_CTRL = 2;
	private static final int MOD_META = 4;
	private static final int MOD_SHIFT = 8;

	/** CDP's own button mask: one is left, two is right. */
	private static final int BUTTON_LEFT = 1;
	private static final int BUTTON_RIGHT = 2;

	/** How far a player can be and still point at a screen; the server's own knob. */
	private static volatile double reach = 6.0;

	/** A wheel notch, in the pixels a web page expects to scroll. */
	private static final int WHEEL_PIXELS = 100;

	/** Toggles typing, so the keyboard stops being movement and becomes a keyboard. */
	private static final int TYPING_KEY = GLFW.GLFW_KEY_F4;

	/** A pointer move is worth sending at most this often. */
	private static final long MOVE_INTERVAL_MS = 40L;

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	/** What the mod knows about where the screens are. */
	public interface Screens {

		/**
		 * Every screen that is drawable here and now.
		 *
		 * @return the screens, in no particular order
		 */
		List<Target> targets();
	}

	/**
	 * One screen, as something that can be pointed at.
	 *
	 * The pixel size is the wall's own grid, not the size frames arrive at: the
	 * server converts to capture space itself, and sending capture coordinates
	 * would put the cursor somewhere else entirely on a scaled-down screen.
	 */
	public record Target(String name, ScreenQuad quad, int pixelWidth, int pixelHeight,
		boolean keyboard) {
	}

	private final Screens screens;

	private GLFWMouseButtonCallback previousMouse;
	private GLFWScrollCallback previousScroll;
	private GLFWKeyCallback previousKey;
	private GLFWCharCallback previousChar;

	private static volatile boolean onScreen;

	private boolean hooked;
	private boolean typing;
	private int held;

	/** The screen a press landed on; a drag stays with it until the release. */
	private String dragging;

	private String lastScreen;
	private int lastX = -1;
	private int lastY = -1;
	private long lastMoveAt;

	private ScreenInput(Screens screens) {
		this.screens = screens;
	}

	/**
	 * Starts listening.
	 *
	 * @param screens where to ask what can be pointed at
	 */
	public static void install(Screens screens) {
		ScreenInput input = new ScreenInput(screens);

		// Not now: Minecraft installs its own GLFW callbacks while it is starting,
		// and whichever of us goes last wins. Waiting for a tick puts this after
		// all of them, so the chain runs through us and on into the game.
		ClientTickEvents.END_CLIENT_TICK.register(client -> input.tick());
	}

	private void tick() {
		if (!hooked) {
			hook();
		}

		Aim aim = aim();
		boolean controlling = crouching();

		onScreen = aim != null;

		// the marks by the crosshair say what the screen has taken over
		HudCursor.scroll(aim != null && controlling);
		HudCursor.keyboard(aim != null && ready() && (typing || aim.keyboard()));

		if (aim == null) {
			return;
		}

		// Hover is only sent while crouching, or while a button is held. Streaming
		// it whenever somebody happens to face the wall is wrong twice over: one
		// browser has one cursor, so several players idly looking at a screen
		// would drag it back and forth between them, and a video played full
		// screen treats any pointer movement as "the viewer is here" and keeps its
		// controls on top of the picture for everyone.
		if (!controlling && held == 0) {
			return;
		}

		long now = System.currentTimeMillis();

		if (aim.name.equals(lastScreen) && aim.x == lastX && aim.y == lastY) {
			return;
		}

		if (now - lastMoveAt < MOVE_INTERVAL_MS) {
			return;
		}

		lastScreen = aim.name;
		lastX = aim.x;
		lastY = aim.y;
		lastMoveAt = now;
		send(aim.name, POINTER_MOVE, out -> {
			out.writeInt(aim.x);
			out.writeInt(aim.y);
			out.writeInt(held);
		});
	}

	private void hook() {
		Minecraft client = Minecraft.getInstance();

		if (client.getWindow() == null) {
			return;
		}

		long window = client.getWindow().handle();

		hooked = true;
		previousMouse = GLFW.glfwSetMouseButtonCallback(window, this::onMouseButton);
		previousScroll = GLFW.glfwSetScrollCallback(window, this::onScroll);
		previousKey = GLFW.glfwSetKeyCallback(window, this::onKey);
		previousChar = GLFW.glfwSetCharCallback(window, this::onChar);
		LOGGER.info("Luna TV is listening for input");
	}

	private void onMouseButton(long window, int button, int action, int mods) {
		if (!consumeMouse(button, action)) {
			if (previousMouse != null) {
				previousMouse.invoke(window, button, action, mods);
			}
		}
	}

	private boolean consumeMouse(int button, int action) {
		int mask = switch (button) {
			case GLFW.GLFW_MOUSE_BUTTON_LEFT -> BUTTON_LEFT;
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> BUTTON_RIGHT;
			default -> 0;
		};

		if (mask == 0) {
			return false;
		}

		if (action == GLFW.GLFW_PRESS) {
			Aim aim = aim();

			if (aim == null) {
				return false;
			}

			held |= mask;
			dragging = aim.name;
			send(aim.name, POINTER_DOWN, out -> {
				out.writeInt(aim.x);
				out.writeInt(aim.y);
				out.writeBoolean(mask == BUTTON_RIGHT);
			});

			return true;
		}

		if (action != GLFW.GLFW_RELEASE || (held & mask) == 0) {
			return false;
		}

		// The release belongs to whichever screen the press landed on, even if the
		// player has looked away since. That is what makes a drag off the edge of
		// a slider behave the way it does in a browser.
		Aim aim = aimAt(dragging);

		held &= ~mask;

		String target = dragging;

		if (held == 0) {
			dragging = null;
		}

		if (aim == null || target == null) {
			return true;
		}

		send(target, POINTER_UP, out -> {
			out.writeInt(aim.x);
			out.writeInt(aim.y);
			out.writeBoolean(mask == BUTTON_RIGHT);
		});

		return true;
	}

	private void onScroll(long window, double xoffset, double yoffset) {
		// Only while crouching. The wheel is the hotbar first and a scrollbar
		// second: taking it whenever somebody happens to face a screen would break
		// item switching anywhere near one.
		Aim aim = crouching() ? aim() : null;

		if (aim == null || yoffset == 0.0) {
			if (previousScroll != null) {
				previousScroll.invoke(window, xoffset, yoffset);
			}

			return;
		}

		// GLFW counts a notch up as positive; a page scrolls down on positive
		int delta = (int) Math.round(-yoffset * WHEEL_PIXELS);

		send(aim.name, SCROLL, out -> {
			out.writeInt(aim.x);
			out.writeInt(aim.y);
			out.writeInt(delta);
		});
	}

	private void onKey(long window, int key, int scancode, int action, int mods) {
		if (key == TYPING_KEY && action == GLFW.GLFW_PRESS) {
			typing = !typing;
			LOGGER.info("Luna TV keyboard is {}", typing ? "on the screen" : "back in the game");

			return;
		}

		if ((typing || focused()) && key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
			typing = false;

			return;
		}

		boolean capture = typing || focused();

		if (!capture || !ready()) {
			if (previousKey != null) {
				previousKey.invoke(window, key, scancode, action, mods);
			}

			return;
		}

		if (action == GLFW.GLFW_RELEASE) {
			return;
		}

		int modifiers = modifiers(mods);
		Aim aim = aim();

		if (aim == null) {
			return;
		}

		// Paste is the one chord that cannot travel as a chord. The clipboard is
		// on this machine and the browser is on the server's, so ctrl+v there
		// would paste whatever the server last copied, which is nothing. Sending
		// the text is the only thing that means what the player intended.
		if ((modifiers & (MOD_CTRL | MOD_META)) != 0 && key == GLFW.GLFW_KEY_V) {
			String clipboard = GLFW.glfwGetClipboardString(window);

			if (clipboard != null && !clipboard.isEmpty()) {
				send(aim.name, TEXT, out -> out.writeUTF(clipboard));
			}

			return;
		}

		String named = name(key, modifiers);

		if (named == null) {
			return;
		}

		send(aim.name, KEY, out -> {
			out.writeUTF(named);
			out.writeInt(modifiers);
		});
	}

	/** GLFW's modifier bits are not CDP's, so they are translated rather than passed. */
	private static int modifiers(int mods) {
		int result = 0;

		if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) {
			result |= MOD_SHIFT;
		}

		if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) {
			result |= MOD_CTRL;
		}

		if ((mods & GLFW.GLFW_MOD_ALT) != 0) {
			result |= MOD_ALT;
		}

		if ((mods & GLFW.GLFW_MOD_SUPER) != 0) {
			result |= MOD_META;
		}

		return result;
	}

	private void onChar(long window, int codepoint) {
		Aim aim = typing || focused() ? aim() : null;

		if (aim == null) {
			if (previousChar != null) {
				previousChar.invoke(window, codepoint);
			}

			return;
		}

		send(aim.name, TEXT, out -> out.writeUTF(new String(Character.toChars(codepoint))));
	}

	/**
	 * What to send for a key press, or null to leave it to the character event.
	 *
	 * A plain letter is left alone: it arrives as text, already cased and already
	 * through the player's own keyboard layout, which a key code cannot tell us.
	 * Held with ctrl, alt or meta it produces no character at all, so the letter
	 * has to travel as a key or a chord like ctrl+a never reaches the page.
	 */
	private static String name(int key, int modifiers) {
		boolean chord = (modifiers & (MOD_CTRL | MOD_ALT | MOD_META)) != 0;

		if (chord && key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
			return String.valueOf((char) ('a' + key - GLFW.GLFW_KEY_A));
		}

		if (chord && key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
			return String.valueOf((char) ('0' + key - GLFW.GLFW_KEY_0));
		}

		return switch (key) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> "enter";
			case GLFW.GLFW_KEY_BACKSPACE -> "backspace";
			case GLFW.GLFW_KEY_DELETE -> "delete";
			case GLFW.GLFW_KEY_TAB -> "tab";
			case GLFW.GLFW_KEY_UP -> "up";
			case GLFW.GLFW_KEY_DOWN -> "down";
			case GLFW.GLFW_KEY_LEFT -> "left";
			case GLFW.GLFW_KEY_RIGHT -> "right";
			case GLFW.GLFW_KEY_HOME -> "home";
			case GLFW.GLFW_KEY_END -> "end";
			case GLFW.GLFW_KEY_PAGE_UP -> "pageup";
			case GLFW.GLFW_KEY_PAGE_DOWN -> "pagedown";
			default -> null;
		};
	}

	/**
	 * Sets how far a player may be and still control a screen.
	 *
	 * @param blocks the server's interact distance
	 */
	public static void reach(double blocks) {
		reach = Math.max(1.0, blocks);
	}

	/** Whether the crosshair is on a screen; the block outline defers to this. */
	public static boolean aiming() {
		return onScreen;
	}

	private static boolean crouching() {
		Minecraft client = Minecraft.getInstance();

		return client.player != null && client.player.isShiftKeyDown();
	}

	/** Whether the screen being looked at has a text field waiting for typing. */
	private boolean focused() {
		Aim aim = aim();

		return aim != null && aim.keyboard();
	}

	private boolean ready() {
		Minecraft client = Minecraft.getInstance();

		// Not while a menu is open: the chat box and the inventory are the game's
		// keyboard, not the browser's. Mouse grab is the test rather than whether a
		// screen is open, both because it is the same call on both game lines and
		// because it is the truer question - if the cursor is free, the player is
		// pointing at something other than the world.
		return client.player != null
			&& client.level != null
			&& client.mouseHandler != null
			&& client.mouseHandler.isMouseGrabbed();
	}

	/** Where the crosshair is, or null when it is not on a screen. */
	private Aim aim() {
		return aimAt(null);
	}

	/**
	 * Where the crosshair is on one screen, or on the nearest one.
	 *
	 * @param only the screen a drag is bound to, or null to take the nearest
	 */
	private Aim aimAt(String only) {
		if (!ready()) {
			return null;
		}

		Minecraft client = Minecraft.getInstance();
		Vec3 eye = client.player.getEyePosition(1.0f);
		Vec3 look = client.player.getViewVector(1.0f);
		List<Target> targets = screens.targets();

		Aim best = null;
		double nearest = reach;

		for (Target target : targets) {
			if (only != null && !only.equals(target.name())) {
				continue;
			}

			double[] hit = target.quad().project(eye.x, eye.y, eye.z, look.x, look.y, look.z);

			if (hit == null || hit[0] > nearest) {
				continue;
			}

			// A drag keeps its screen wherever the aim has wandered to; a fresh
			// look has to actually land on the picture.
			if (only == null && (hit[1] < 0.0 || hit[1] > 1.0 || hit[2] < 0.0 || hit[2] > 1.0)) {
				continue;
			}

			nearest = hit[0];
			best = new Aim(target.name(),
				pixel(hit[1], target.pixelWidth()), pixel(hit[2], target.pixelHeight()),
				target.keyboard());
		}

		if (best == null || only != null) {
			return best;
		}

		// Anything solid in front of the screen wins the click. Without this a
		// player could not hit a mob standing between them and the wall.
		HitResult blocking = client.hitResult;

		if (blocking != null && blocking.getType() != HitResult.Type.MISS
			&& blocking.getLocation().distanceTo(eye) < nearest - 0.05) {
			return null;
		}

		return best;
	}

	private static int pixel(double fraction, int size) {
		return Math.max(0, Math.min(size - 1, (int) Math.floor(fraction * size)));
	}

	private void send(String screen, byte kind, Body body) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);

			out.writeUTF(screen);
			out.writeByte(kind);
			body.write(out);
			ClientPlayNetworking.send(new TvPayload(TvPayload.INPUT, bytes.toByteArray()));
		} catch (IOException impossible) {
			LOGGER.error("Luna TV could not send input", impossible);
		} catch (IllegalStateException notConnected) {
			// the channel goes away the moment the player leaves; a keystroke that
			// lands in that window is not worth a stack trace
		}
	}

	private interface Body {

		void write(DataOutputStream out) throws IOException;
	}

	private record Aim(String name, int x, int y, boolean keyboard) {
	}
}
