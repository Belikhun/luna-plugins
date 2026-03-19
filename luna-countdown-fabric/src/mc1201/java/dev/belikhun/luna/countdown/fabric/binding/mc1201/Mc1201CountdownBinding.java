package dev.belikhun.luna.countdown.fabric.binding.mc1201;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.countdown.fabric.LunaCountdownFabricRuntime;
import dev.belikhun.luna.countdown.fabric.binding.FabricCountdownFamilyBinding;
import dev.belikhun.luna.countdown.fabric.binding.command.FabricCountdownCommandBindingSupport;
import dev.belikhun.luna.countdown.fabric.binding.event.FabricCountdownPlayerEventBindingSupport;

public final class Mc1201CountdownBinding implements FabricCountdownFamilyBinding {
	@Override
	public void bind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family) {
		FabricCountdownCommandBindingSupport.register(runtime.commandService());
		FabricCountdownPlayerEventBindingSupport.register(runtime.countdownService());
	}

	@Override
	public void unbind(LunaCountdownFabricRuntime runtime, FabricVersionFamily family) {
	}
}
