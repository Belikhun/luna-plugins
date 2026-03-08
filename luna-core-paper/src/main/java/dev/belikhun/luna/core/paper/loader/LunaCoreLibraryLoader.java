package dev.belikhun.luna.core.paper.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

public final class LunaCoreLibraryLoader implements PluginLoader {
	private static final Logger LOGGER = Logger.getLogger("LunaCoreLoader");
	private static final String SQLITE_COORDINATE = "org.xerial:sqlite-jdbc:3.47.2.0";
	private static final String MYSQL_COORDINATE = "com.mysql:mysql-connector-j:9.1.0";
	private static final String MARIADB_COORDINATE = "org.mariadb.jdbc:mariadb-java-client:3.4.1";

	@Override
	public void classloader(PluginClasspathBuilder classpathBuilder) {
		MavenLibraryResolver resolver = new MavenLibraryResolver();
		resolver.addRepository(new RemoteRepository.Builder("paper", "default", "https://repo.papermc.io/repository/maven-public/").build());
		resolver.addRepository(new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

		String configuredType = readDatabaseType();
		LOGGER.info("[LunaCore] Loader database.type = " + configuredType);
		switch (configuredType) {
			case "mysql" -> addCoordinate(resolver, MYSQL_COORDINATE, "mysql");
			case "mariadb", "maria_db" -> addCoordinate(resolver, MARIADB_COORDINATE, "mariadb");
			case "sqlite", "sqlite3" -> addCoordinate(resolver, SQLITE_COORDINATE, "sqlite");
			default -> {
				LOGGER.warning("[LunaCore] Unknown database.type='" + configuredType + "', loading sqlite/mysql/mariadb drivers as fallback.");
				addCoordinate(resolver, SQLITE_COORDINATE, "sqlite");
				addCoordinate(resolver, MYSQL_COORDINATE, "mysql");
				addCoordinate(resolver, MARIADB_COORDINATE, "mariadb");
			}
		}

		classpathBuilder.addLibrary(resolver);
	}

	private static void addCoordinate(MavenLibraryResolver resolver, String coordinate, String label) {
		LOGGER.info("[LunaCore] Resolving JDBC library for " + label + ": " + coordinate);
		resolver.addDependency(new Dependency(new DefaultArtifact(coordinate), null));
	}

	private static String readDatabaseType() {
		Path configPath = Path.of("plugins", "LunaCore", "config.yml");
		if (!Files.isRegularFile(configPath)) {
			return "sqlite";
		}

		YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configPath.toFile());
		ConfigurationSection databaseSection = configuration.getConfigurationSection("database");
		if (databaseSection == null) {
			return "sqlite";
		}

		String configuredType = databaseSection.getString("type", "sqlite");
		return configuredType.trim().toLowerCase(Locale.ROOT);
	}
}


