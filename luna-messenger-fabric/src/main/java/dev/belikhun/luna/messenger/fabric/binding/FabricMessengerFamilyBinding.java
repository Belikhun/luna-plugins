package dev.belikhun.luna.messenger.fabric.binding;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.messenger.fabric.LunaMessengerFabricRuntime;

public interface FabricMessengerFamilyBinding {

	void bind(LunaMessengerFabricRuntime runtime, FabricVersionFamily family);

	void unbind(LunaMessengerFabricRuntime runtime, FabricVersionFamily family);
}
