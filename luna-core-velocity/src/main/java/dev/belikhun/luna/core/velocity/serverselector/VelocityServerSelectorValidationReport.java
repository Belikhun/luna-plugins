package dev.belikhun.luna.core.velocity.serverselector;

import java.util.List;

public record VelocityServerSelectorValidationReport(
	List<String> errors,
	List<String> warnings
) {
	public boolean hasErrors() {
		return !errors.isEmpty();
	}

	public boolean hasWarnings() {
		return !warnings.isEmpty();
	}
}
