package dev.belikhun.luna.core.fabric;

import net.fabricmc.loader.api.FabricLoader;

public final class FabricRuntimeEnvironment {
	private FabricRuntimeEnvironment() {
	}

	public static FabricVersionFamily detectCurrentFamily() {
		String version = FabricLoader.getInstance()
			.getModContainer("minecraft")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElseThrow(() -> new IllegalStateException("Unable to resolve the current Minecraft version from Fabric Loader."));

		if (version.startsWith("1.21")) {
			return FabricVersionFamily.MC121X;
		}
		if (version.startsWith("1.20.1")) {
			return FabricVersionFamily.MC1201;
		}
		if (version.startsWith("1.19.")) {
			return FabricVersionFamily.MC119X;
		}
		if (version.startsWith("1.18.2")) {
			return FabricVersionFamily.MC1182;
		}
		if (version.startsWith("1.16.5")) {
			return FabricVersionFamily.MC1165;
		}

		throw new IllegalStateException("Unsupported Fabric runtime Minecraft version: " + version);
	}
}