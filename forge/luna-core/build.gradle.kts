plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The 1.20.1 build of luna-core. Classic forge, not neoforge: a separate loader
// with its own event bus and its own descriptor, so only the bootstrap differs.
//
// Sources are mojmap here exactly as they are on neoforge and fabric, and the
// legacyforge plugin reobfuscates them to SRG on the way into the jar - which is
// what lets this module compile the same trunk the other loaders do.
val lunaCoreMc = rootProject.layout.projectDirectory.dir("core/luna-core-mc/src")

// 1.20.1 predates data components and the registry-aware item codec, and still
// has the public ResourceLocation constructor, so this line's compat sets differ
// from the 1.21 ones in three of five. core/luna-core-mc/README.md is the table.
sourceSets.named("main") {
	java.srcDir(lunaCoreMc.dir("main/java"))
	java.srcDir(lunaCoreMc.dir("player-1x/java"))
	java.srcDir(lunaCoreMc.dir("menu-clicktype/java"))
	java.srcDir(lunaCoreMc.dir("registry-ctor/java"))
	java.srcDir(lunaCoreMc.dir("decor-nbt/java"))
	java.srcDir(lunaCoreMc.dir("itemio-save/java"))
	java.srcDir(lunaCoreMc.dir("text-serializer/java"))
	java.srcDir(lunaCoreMc.dir("services/java"))
}

dependencies {
	implementation(project(":luna-core-api"))
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)
	compileOnly(libs.adventure.serializer.gson)
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
	compileOnly(libs.voicechat.api)
	compileOnly(libs.mariadb.jdbc)
}

val embeddedAdventureMiniMessage = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.minimessage.get())
)

val embeddedAdventureSerializerLegacy = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.serializer.legacy.get())
)

val embeddedAdventureSerializerGson = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.serializer.gson.get())
)

val embeddedSnakeYaml = configurations.detachedConfiguration(
	dependencies.create("org.yaml:snakeyaml:2.2")
)

// The jdbc driver ships inside the jar for the reason it does on every mod
// platform: there is no plugin loader to fetch one at boot. Deliberately not
// relocated - DatabaseType names the driver class as a string for Class.forName.
val embeddedMariaDbDriver = configurations.detachedConfiguration(
	dependencies.create(libs.mariadb.jdbc.get())
).apply {
	isTransitive = false
}

legacyForge {
	version = libs.versions.forge201.get()
}

// shadow runs first and stays in build/libs; the reobf task takes its output and
// writes the deliverable, because a forge mod has to be reobfuscated after
// everything it carries is already inside it. Same shape as the fabric modules'
// shadowJar -> remapJar chain, and for the same reason.
//
// Without this the jar loads in a dev environment and dies on a live server: the
// classes would still be naming mojmap members that only exist there under their
// SRG ids.
val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-core-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave a mojmap jar behind that no forge server can run
tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}

tasks.named<ShadowJar>("shadowJar") {
	val coreApiJar = project(":luna-core-api").tasks.named<Jar>("jar")
	dependsOn(coreApiJar)
	configurations = project.provider {
		listOf(
			embeddedAdventureMiniMessage,
			embeddedAdventureSerializerLegacy,
			embeddedAdventureSerializerGson,
			embeddedSnakeYaml,
			embeddedMariaDbDriver
		)
	}
	from(zipTree(coreApiJar.get().archiveFile.get().asFile))
	mergeServiceFiles()
	relocate("net.kyori.adventure", "dev.belikhun.luna.shadow.net.kyori.adventure")
	relocate("net.kyori.examination", "dev.belikhun.luna.shadow.net.kyori.examination")
	relocate("net.kyori.option", "dev.belikhun.luna.shadow.net.kyori.option")
	relocate("org.yaml.snakeyaml", "dev.belikhun.luna.shadow.snakeyaml")
	exclude("META-INF/MANIFEST.MF")
	exclude("com/google/gson/**")
	exclude("META-INF/maven/com.google.code.gson/**")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-core-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}
