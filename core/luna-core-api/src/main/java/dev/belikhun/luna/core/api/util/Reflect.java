package dev.belikhun.luna.core.api.util;

/**
 * Best-effort reflective reads against an object whose exact shape varies with
 * the game version.
 *
 * Only usable where the runtime keeps readable names - NeoForge does, Fabric
 * remaps to intermediary and a lookup by name there finds nothing, which is why
 * the fabric core makes compiled calls instead. Every method answers a fallback
 * rather than throwing: a stat that cannot be read is one blank cell in the
 * console, not a failed heartbeat.
 */
public final class Reflect {
	private Reflect() {
	}

	/** Invoke a no-argument method and read it as a number, or null. */
	public static Double callDouble(Object target, String methodName) {
		if (target == null) {
			return null;
		}

		try {
			Object value = target.getClass().getMethod(methodName).invoke(target);
			return value instanceof Number number ? number.doubleValue() : null;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	/** Read a field by name, walking up the hierarchy; null when absent. */
	public static Object field(Object target, String fieldName) {
		if (target == null) {
			return null;
		}

		Class<?> type = target.getClass();
		while (type != null) {
			try {
				var field = type.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field.get(target);
			} catch (ReflectiveOperationException ignored) {
				type = type.getSuperclass();
			}
		}
		return null;
	}
}
