package dev.belikhun.luna.countdown.fabric.binding;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.countdown.fabric.LunaCountdownFabricRuntime;

public interface FabricCountdownFamilyBinding {

	void bind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family);

	void unbind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family);
}
