package dev.belikhun.luna.core.fabric.lifecycle;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class FabricConsoleColorSupport {
	private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);
	private static final int STD_OUTPUT_HANDLE = -11;
	private static final int STD_ERROR_HANDLE = -12;
	private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;
	private static final int DISABLE_NEWLINE_AUTO_RETURN = 0x0008;

	private FabricConsoleColorSupport() {
	}

	static void install() {
		if (!ATTEMPTED.compareAndSet(false, true)) {
			return;
		}
		if (!isWindows()) {
			return;
		}

		try {
			Kernel32 kernel32 = Kernel32.INSTANCE;
			boolean stdoutEnabled = enableVirtualTerminal(kernel32, STD_OUTPUT_HANDLE);
			boolean stderrEnabled = enableVirtualTerminal(kernel32, STD_ERROR_HANDLE);
			if (stdoutEnabled || stderrEnabled) {
				writeProbe();
			}
		} catch (Throwable ignored) {
			// Console VT support is optional; fall back to plain raw output if the bridge is unavailable.
		}
	}

	private static boolean isWindows() {
		String osName = System.getProperty("os.name", "");
		return osName.toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean enableVirtualTerminal(Kernel32 kernel32, int handleType) {
		if (kernel32 == null) {
			return false;
		}

		long handle = kernel32.GetStdHandle(handleType);
		if (handle == 0L || handle == -1L) {
			return false;
		}

		int[] mode = new int[1];
		if (!kernel32.GetConsoleMode(handle, mode)) {
			return false;
		}

		int updatedMode = mode[0] | ENABLE_VIRTUAL_TERMINAL_PROCESSING | DISABLE_NEWLINE_AUTO_RETURN;
		if (updatedMode == mode[0]) {
			return true;
		}

		return kernel32.SetConsoleMode(handle, updatedMode);
	}

	private static void writeProbe() {
		try {
			OutputStream stream = new FileOutputStream(FileDescriptor.out);
			stream.write("\u001B[0m".getBytes(StandardCharsets.UTF_8));
			stream.flush();
		} catch (IOException ignored) {
			// Ignore probe failures; VT mode may still be active.
		}
	}

	private interface Kernel32 extends Library {
		Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

		long GetStdHandle(int nStdHandle);

		boolean GetConsoleMode(long hConsoleHandle, int[] lpMode);

		boolean SetConsoleMode(long hConsoleHandle, int dwMode);
	}
}