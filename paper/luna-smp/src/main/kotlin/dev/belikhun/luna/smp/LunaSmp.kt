package dev.belikhun.luna.smp

import xyz.xenondevs.nova.addon.Addon

/**
 * The Nova addon half of LunaSmp: custom items and furniture blocks, whose
 * assets travel in Nova's generated resource pack. The classic plugin half
 * (pack protect, farm protect, tool repair) stays in [LunaSmpPlugin], which
 * the generated paper-plugin.yml keeps as the plugin main.
 */
object LunaSmp : Addon()
