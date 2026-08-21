package dev.belikhun.luna.tv.screen;

/** Where a screen is in its lifecycle, and what the wall therefore shows. */
public enum ScreenState {

	/** Powered down on purpose: no browser, the wall shows black. */
	OFF,
	/** The world holding the screen is not loaded; nothing is drawn. */
	SUSPENDED,
	/** Chromium is being launched. */
	STARTING,
	/** A browser is attached and painting. */
	RUNNING,
	/** The browser died and a relaunch is pending or exhausted. */
	CRASHED;

	/** Whether a browser frame is the expected content for this state. */
	public boolean live() {
		return this == RUNNING;
	}

	/** Human label, for the GUI and the list. */
	public String label() {
		return switch (this) {
			case OFF -> "đã tắt";
			case SUSPENDED -> "tạm dừng";
			case STARTING -> "đang mở";
			case RUNNING -> "đang chạy";
			case CRASHED -> "gặp lỗi";
		};
	}
}
