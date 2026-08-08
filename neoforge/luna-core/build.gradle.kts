plugins {
	alias(libs.plugins.moddevgradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

dependencies {
	implementation(project(":luna-core-api"))
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)
	compileOnly(libs.adventure.serializer.gson)
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
	compileOnly(libs.voicechat.api)
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
			embeddedSnakeYaml
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
