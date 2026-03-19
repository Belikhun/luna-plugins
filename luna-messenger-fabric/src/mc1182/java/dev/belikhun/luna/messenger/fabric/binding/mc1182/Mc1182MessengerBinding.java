package dev.belikhun.luna.messenger.fabric.binding.mc1182;

import dev.belikhun.luna.core.fabric.FabricVersionFamily;
import dev.belikhun.luna.messenger.fabric.LunaMessengerFabricRuntime;
import dev.belikhun.luna.messenger.fabric.binding.FabricMessengerFamilyBinding;
import dev.belikhun.luna.messenger.fabric.binding.command.FabricMessengerCommandBindingSupport;
import dev.belikhun.luna.messenger.fabric.binding.event.FabricMessengerChatBindingSupport;

public final class Mc1182MessengerBinding implements FabricMessengerFamilyBinding {
	@Override
	public void bind(LunaMessengerFabricRuntime runtime, FabricVersionFamily family) {
		FabricMessengerCommandBindingSupport.register(runtime.commandService());
		FabricMessengerChatBindingSupport.register(runtime.commandService());
	}

	@Override
	public void unbind(LunaMessengerFabricRuntime runtime, FabricVersionFamily family) {
	}
}
