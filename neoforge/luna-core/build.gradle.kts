plugins {
	alias(libs.plugins.moddevgradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The platform-free half of the luna UI toolkit, shared by source because it is
// written against net.minecraft and no plain jar can see that; see
// core/luna-core-mc/README.md.
//
// NeoForge 21.1 is Minecraft 1.21, so it takes the same compat sets as the 1.21
// fabric build: ClickType for the menu override, the factory method for a
// ResourceLocation. The forge modules differ only in this list.
val lunaCoreMc = rootProject.layout.projectDirectory.dir("core/luna-core-mc/src")

sourceSets.named("main") {
	java.srcDir(lunaCoreMc.dir("main/java"))
	java.srcDir(lunaCoreMc.dir("player-1x/java"))
	java.srcDir(lunaCoreMc.dir("menu-clicktype/java"))
	java.srcDir(lunaCoreMc.dir("registry-namespaced/java"))
	java.srcDir(lunaCoreMc.dir("decor-components/java"))
	java.srcDir(lunaCoreMc.dir("itemio-codec/java"))
	java.srcDir(lunaCoreMc.dir("text-codec/java"))
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

// The jdbc driver ships inside the jar for the same reason it does on fabric: a
// mod has no plugin loader to fetch one at boot. Deliberately not relocated -
// DatabaseType names the driver class as a string for Class.forName.
val embeddedMariaDbDriver = configurations.detachedConfiguration(
	dependencies.create(libs.mariadb.jdbc.get())
).apply {
	isTransitive = false
}

neoForge {
	version = libs.versions.neoforge.get()
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
}
