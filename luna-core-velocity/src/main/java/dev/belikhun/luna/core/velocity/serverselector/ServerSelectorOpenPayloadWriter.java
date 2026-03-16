package dev.belikhun.luna.core.velocity.serverselector;

import dev.belikhun.luna.core.api.messaging.PluginMessageWriter;

public final class ServerSelectorOpenPayloadWriter {
	private ServerSelectorOpenPayloadWriter() {
	}

	public static void write(PluginMessageWriter writer, VelocityServerSelectorConfig config) {
		writer.writeUtf("open-v7");
		writer.writeUtf(config.guiTitle());
		writer.writeUtf(config.template().name());
		writeLines(writer, config.template().headerLines());
		writer.writeUtf(config.template().bodyLine());
		writeLines(writer, config.template().footerLines());
		writer.writeUtf(config.template().material());
		writeTemplateOverrides(writer, config.template().byStatus());
		writeStatusStyles(writer, config);

		writer.writeInt(config.servers().size());
		for (VelocityServerSelectorConfig.ServerDefinition server : config.servers().values()) {
			writer.writeUtf(server.backendName());
			writer.writeUtf(server.displayName());
			writer.writeUtf(server.accentColor());
			writer.writeUtf(server.permission());
			writer.writeInt(server.slot() == null ? -1 : server.slot());
			writer.writeInt(server.page() == null ? -1 : server.page());
			writer.writeUtf(server.material());
			writer.writeInt(server.materialByStatus().size());
			for (var materialEntry : server.materialByStatus().entrySet()) {
				writer.writeUtf(materialEntry.getKey().name());
				writer.writeUtf(materialEntry.getValue() == null ? "" : materialEntry.getValue());
			}
			writer.writeBoolean(server.glint() != null);
			if (server.glint() != null) {
				writer.writeBoolean(server.glint());
			}
			writer.writeInt(server.glintByStatus().size());
			for (var glintEntry : server.glintByStatus().entrySet()) {
				writer.writeUtf(glintEntry.getKey().name());
				writer.writeBoolean(Boolean.TRUE.equals(glintEntry.getValue()));
			}
			writer.writeInt(server.conditional().size());
			for (VelocityServerSelectorConfig.ConditionalOverride conditional : server.conditional()) {
				writer.writeUtf(conditional.when() == null ? "" : conditional.when());
				writer.writeBoolean(conditional.material() != null);
				if (conditional.material() != null) {
					writer.writeUtf(conditional.material());
				}
				writer.writeBoolean(conditional.glint() != null);
				if (conditional.glint() != null) {
					writer.writeBoolean(conditional.glint());
				}
				writer.writeBoolean(conditional.description() != null);
				if (conditional.description() != null) {
					writeLines(writer, conditional.description());
				}
				writer.writeBoolean(conditional.template() != null);
				if (conditional.template() != null) {
					writeTemplateOverride(writer, conditional.template());
				}
			}
			writer.writeInt(server.description().size());
			for (String line : server.description()) {
				writer.writeUtf(line == null ? "" : line);
			}
			writer.writeInt(server.descriptionByStatus().size());
			for (var entry : server.descriptionByStatus().entrySet()) {
				writer.writeUtf(entry.getKey().name());
				writeLines(writer, entry.getValue());
			}

			VelocityServerSelectorConfig.ServerTemplate template = server.template();
			writer.writeBoolean(template != null);
			if (template != null) {
				writer.writeUtf(template.name());
				writeLines(writer, template.headerLines());
				writer.writeUtf(template.bodyLine());
				writeLines(writer, template.footerLines());
				writer.writeUtf(template.material());
				writeTemplateOverrides(writer, template.byStatus());
			}
		}
	}

	private static void writeLines(PluginMessageWriter writer, java.util.List<String> lines) {
		writer.writeInt(lines == null ? 0 : lines.size());
		if (lines == null) {
			return;
		}
		for (String line : lines) {
			writer.writeUtf(line == null ? "" : line);
		}
	}

	private static void writeTemplateOverrides(
		PluginMessageWriter writer,
		java.util.Map<ServerSelectorStatus, VelocityServerSelectorConfig.TemplateOverride> overrides
	) {
		writer.writeInt(overrides == null ? 0 : overrides.size());
		if (overrides == null) {
			return;
		}
		for (var entry : overrides.entrySet()) {
			writer.writeUtf(entry.getKey().name());
			writeTemplateOverride(writer, entry.getValue());
		}
	}

	private static void writeTemplateOverride(
		PluginMessageWriter writer,
		VelocityServerSelectorConfig.TemplateOverride override
	) {
		writer.writeBoolean(override.name() != null);
		if (override.name() != null) {
			writer.writeUtf(override.name());
		}
		writer.writeBoolean(override.headerLines() != null);
		if (override.headerLines() != null) {
			writeLines(writer, override.headerLines());
		}
		writer.writeBoolean(override.bodyLine() != null);
		if (override.bodyLine() != null) {
			writer.writeUtf(override.bodyLine());
		}
		writer.writeBoolean(override.footerLines() != null);
		if (override.footerLines() != null) {
			writeLines(writer, override.footerLines());
		}
	}

	private static void writeStatusStyles(PluginMessageWriter writer, VelocityServerSelectorConfig config) {
		ServerSelectorStatus[] statuses = ServerSelectorStatus.values();
		writer.writeInt(statuses.length);
		for (ServerSelectorStatus status : statuses) {
			writer.writeUtf(status.name());
			writer.writeUtf(config.color(status));
			writer.writeUtf(config.icon(status));
		}
	}
}
