package dev.belikhun.luna.core.fabric.placeholder;

import dev.belikhun.luna.core.api.placeholder.PlaceholderProvider;

import net.minecraft.server.level.ServerPlayer;

/**
 * The shared provider contract bound to this platform's service and player types.
 *
 * It exists so the providers and the collections holding them can be written
 * without repeating both type arguments; the routing itself lives in
 * {@link dev.belikhun.luna.core.api.placeholder.PlaceholderRouting}.
 */
interface FabricPlaceholderProvider extends PlaceholderProvider<BuiltInFabricPlaceholderService, ServerPlayer> {
}
