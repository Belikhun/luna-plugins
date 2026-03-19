package dev.belikhun.luna.messenger.fabric.binding;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.messenger.fabric.LunaMessengerFabricRuntime;

public final class NoopFabricMessengerFamilyBinding implements FabricMessengerFamilyBinding {
	@Override
	public void bind(LunaMessengerFabricRuntime runtime, FabricVersionFamily family) {
	}

	@Override
	public void unbind(LunaMessengerFabricRuntime runtime, FabricVersionFamily family) {
	}
}
