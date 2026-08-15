plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The 1.12.2 build of luna-core. Unlike every other forge module this shares no
// source with its siblings: that line predates ModLauncher, Brigadier, mojmap and
// the flattening, so `core/luna-core-mc` cannot be srcDir'd in and the trunk under
// src/ here is written against MCP names instead. The architecture is the same; the
// code is not.
//
// RetroFuturaGradle rather than moddevgradle, because MDG only speaks the mojmap
// eras. RFG compiles against MCP `stable_39` and reobfuscates to SRG at packaging,
// which is the same shape as the legacyForge -> reobf chain the 1.20.1 module uses
// and exists for the same reason: a mod naming MCP members runs in a dev workspace
// and dies on a live server.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

dependencies {
	implementation(project(":luna-legacy-api"))
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)
	compileOnly(libs.mariadb.jdbc)
}

val embeddedAdventureMiniMessage = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.minimessage.get())
)

val embeddedAdventureSerializerLegacy = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.serializer.legacy.get())
)

val embeddedSnakeYaml = configurations.detachedConfiguration(
	dependencies.create("org.yaml:snakeyaml:2.2")
)

// Same reasoning as every other mod platform: no plugin loader will fetch a driver
// at boot, and it is deliberately not relocated because DatabaseType names the
// driver class as a string for Class.forName.
val embeddedMariaDbDriver = configurations.detachedConfiguration(
	dependencies.create(libs.mariadb.jdbc.get())
).apply {
	isTransitive = false
}

// There is no adventure-serializer-gson here, unlike the modern modules. 1.12.2 has
// no component json luna would round-trip through and no hex chat to preserve, so
// text is rendered to legacy section-sign strings on the way out and the gson
// serializer would be dead weight in the jar.
tasks.named<ShadowJar>("shadowJar") {
	val legacyApiJar = project(":luna-legacy-api").tasks.named<Jar>("jar")

	dependsOn(legacyApiJar)
	configurations = project.provider {
		listOf(
			embeddedAdventureMiniMessage,
			embeddedAdventureSerializerLegacy,
			embeddedSnakeYaml,
			embeddedMariaDbDriver
		)
	}

	from(zipTree(legacyApiJar.get().archiveFile.get().asFile))
	mergeServiceFiles()
	relocate("net.kyori.adventure", "dev.belikhun.luna.shadow.net.kyori.adventure")
	relocate("net.kyori.examination", "dev.belikhun.luna.shadow.net.kyori.examination")
	relocate("net.kyori.option", "dev.belikhun.luna.shadow.net.kyori.option")
	relocate("org.yaml.snakeyaml", "dev.belikhun.luna.shadow.snakeyaml")
	exclude("META-INF/MANIFEST.MF")
	exclude("com/google/gson/**")
	exclude("META-INF/maven/com.google.code.gson/**")

	// Multi-release class files, which this line can never reach: 1.12.2 runs on
	// Java 8 and always loads the base version. Leaving them in is not harmless -
	// FML's class scanner reads every entry and cannot parse Java 9 bytecode, so
	// each one becomes "probably a corrupt zip" in the log at every boot, which is
	// exactly the line an operator should be able to trust.
	exclude("META-INF/versions/**")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-core-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

// shadow stays in build/libs and the reobf task writes the deliverable, because a
// forge mod is reobfuscated after everything it carries is already inside it. RFG
// spells this `reobfJar` rather than MDG's `obfuscation.reobfuscate`, so the wiring
// differs from forge/luna-core even though the chain is the same.
tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-core-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would leave
// an MCP-named jar behind that no live 1.12.2 server can run
tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
