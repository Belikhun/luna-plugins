package dev.belikhun.luna.tv.client.render;

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Finds an unlit textured render type, whichever version we are on.
 *
 * This is the single thing that differs between 1.21.10 and 1.21.11. Mojang
 * renamed ResourceLocation to Identifier and moved the render types into a class
 * of their own, which sounds like a wide break and is not: intermediary names
 * track a class's identity rather than its name, so after remapping the argument
 * is class_2960 on both and the result is class_1921 on both. Only the factory
 * that makes one moved house, from class_1921 to class_12249.
 *
 * One reflective lookup therefore covers both, and it goes through Fabric's
 * mapping resolver rather than a literal name: in production those classes carry
 * their obfuscated names, so asking for "RenderType" by string finds nothing.
 */
public final class RenderTypeCompat {

	private static final Logger LOGGER = LoggerFactory.getLogger("LunaTV");

	private static final String LOCATION = "net.minecraft.class_2960";
	private static final String DESCRIPTOR = "(Lnet/minecraft/class_2960;Z)Lnet/minecraft/class_1921;";

	/** 1.21.11 and later: RenderTypes.beaconBeam. */
	private static final String[] MOVED = { "net.minecraft.class_12249", "method_75988" };

	/** 1.21.10: the same factory, back when RenderType still owned it. */
	private static final String[] ORIGINAL = { "net.minecraft.class_1921", "method_23592" };

	private static Method factory;
	private static boolean looked;

	private RenderTypeCompat() {
	}

	/**
	 * An unlit, opaque, textured render type.
	 *
	 * The beacon beam is borrowed rather than an entity type because its shader
	 * is the one thing wanted here: it samples the texture, multiplies by the
	 * vertex colour, applies fog, and stops. Entity types run the picture through
	 * the lightmap and through Minecraft's directional shading, which darkens an
	 * east or west facing quad to 0.6 of its colour no matter what light value is
	 * handed in - a screen that dims because of which way the wall points.
	 *
	 * @param texture the picture to draw
	 * @return the render type, or null when this version has neither factory
	 */
	public static RenderType unlit(ResourceLocation texture) {
		Method found = resolve();

		if (found == null) {
			return null;
		}

		try {
			return (RenderType) found.invoke(null, texture, Boolean.FALSE);
		} catch (ReflectiveOperationException failed) {
			LOGGER.error("Luna TV could not build a render type", failed);

			return null;
		}
	}

	private static Method resolve() {
		if (looked) {
			return factory;
		}

		looked = true;

		MappingResolver mappings = FabricLoader.getInstance().getMappingResolver();

		for (String[] candidate : new String[][] { MOVED, ORIGINAL }) {
			factory = lookUp(mappings, candidate[0], candidate[1]);

			if (factory != null) {
				LOGGER.info("Luna TV draws through {}", factory.getDeclaringClass().getName());

				return factory;
			}
		}

		LOGGER.error("Luna TV found no entity render type on this Minecraft version;"
			+ " screens cannot be drawn.");

		return null;
	}

	private static Method lookUp(MappingResolver mappings, String owner, String method) {
		try {
			Class<?> holder = Class.forName(mappings.mapClassName("intermediary", owner));
			Class<?> location = Class.forName(mappings.mapClassName("intermediary", LOCATION));

			return holder.getMethod(
				mappings.mapMethodName("intermediary", owner, method, DESCRIPTOR),
				location, boolean.class);
		} catch (ReflectiveOperationException | LinkageError absent) {
			return null;
		}
	}
}
