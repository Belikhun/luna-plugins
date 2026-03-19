package dev.belikhun.luna.countdown.fabric.binding;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.countdown.fabric.LunaCountdownFabricRuntime;

public final class NoopFabricCountdownFamilyBinding implements FabricCountdownFamilyBinding {
	@Override
	public void bind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family) {
	}

	@Override
	public void unbind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family) {
	}
}
