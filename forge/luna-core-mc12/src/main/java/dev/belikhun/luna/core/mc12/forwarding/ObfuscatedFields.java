package dev.belikhun.luna.core.mc12.forwarding;

import dev.belikhun.luna.legacy.exception.LunaLegacyException;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Reach a private vanilla field, under whichever name the runtime is using.
 *
 * Everything else in this module gets its names for free: we compile against
 * MCP and RFG reobfuscates to SRG on the way into the jar. That does not work
 * for a *private* field, because javac refuses the access before RFG ever sees
 * it - so these are reached reflectively, and a reflective lookup takes a name
 * as a string, which no remapper rewrites.
 *
 * Hence both spellings for each field. A production server runs SRG
 * (`field_147337_i`); a dev workspace runs MCP (`loginGameProfile`). Trying both
 * is what makes one jar work in both places, and it is why each call site names
 * the SRG id first: that is the one that matters on a real server.
 *
 * Lookups are cached because they happen inside a login, and `getDeclaredField`
 * walks and allocates every time.
 */
final class ObfuscatedFields {
	private static final Map<String, Field> CACHE = new HashMap<String, Field>();

	private ObfuscatedFields() {
	}

	/**
	 * @param owner the class declaring the field
	 * @param names the field's names, SRG first, MCP after
	 */
	static synchronized Field find(Class<?> owner, String... names) {
		String key = owner.getName() + "#" + names[0];
		Field cached = CACHE.get(key);

		if (cached != null) {
			return cached;
		}

		for (String name : names) {
			try {
				Field field = owner.getDeclaredField(name);

				field.setAccessible(true);
				CACHE.put(key, field);

				return field;
			} catch (NoSuchFieldException ignored) {
				// try the next spelling
			}
		}

		throw new LunaLegacyException(
			"Không tìm thấy field " + names[0] + " trên " + owner.getName()
				+ "; phiên bản Minecraft/Forge có thể không phải 1.12.2."
		);
	}

	static Object get(Class<?> owner, Object instance, String... names) {
		try {
			return find(owner, names).get(instance);
		} catch (IllegalAccessException failure) {
			throw new LunaLegacyException("Không đọc được field " + names[0] + ".", failure);
		}
	}

	static void set(Class<?> owner, Object instance, Object value, String... names) {
		try {
			find(owner, names).set(instance, value);
		} catch (IllegalAccessException failure) {
			throw new LunaLegacyException("Không ghi được field " + names[0] + ".", failure);
		}
	}
}
