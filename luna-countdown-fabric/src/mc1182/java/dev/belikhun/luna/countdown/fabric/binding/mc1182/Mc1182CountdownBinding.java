package dev.belikhun.luna.countdown.fabric.binding.mc1182;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.countdown.fabric.LunaCountdownFabricRuntime;
import dev.belikhun.luna.countdown.fabric.binding.FabricCountdownFamilyBinding;
import dev.belikhun.luna.countdown.fabric.binding.command.FabricCountdownCommandBindingSupport;

public final class Mc1182CountdownBinding implements FabricCountdownFamilyBinding {
	@Override
	public void bind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family) {
		FabricCountdownCommandBindingSupport.register(runtime.commandService());
	}

	@Override
	public void unbind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family) {
	}
}
