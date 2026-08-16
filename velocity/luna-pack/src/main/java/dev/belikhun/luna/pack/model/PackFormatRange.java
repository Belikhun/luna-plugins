package dev.belikhun.luna.pack.model;

/**
 * The span of pack formats a resource pack declares it can run on, normalized
 * from whichever fields its pack.mcmeta used. `source` names the winning
 * declaration ("min_format", "supported_formats" or "pack_format"); `clamped`
 * marks a legacy declaration whose ceiling we had to pull down to 64, because
 * clients from 1.21.9 reject the file outright when the legacy fields alone
 * claim anything past that.
 */
public record PackFormatRange(
	PackFormat min,
	PackFormat max,
	String source,
	boolean clamped
) {
	public boolean contains(PackFormat format) {
		return format.compareTo(min) >= 0 && format.compareTo(max) <= 0;
	}

	/** min > max: a broken declaration that matches no client at all. */
	public boolean isEmpty() {
		return min.compareTo(max) > 0;
	}

	public String render() {
		if (min.equals(max)) {
			return min.render();
		}
		return min.render() + "-" + max.render();
	}
}
