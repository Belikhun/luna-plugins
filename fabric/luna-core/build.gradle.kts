plugins {
	alias(libs.plugins.fabricloom)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.jvm.tasks.Jar

// One jar serves every game version from 1.20 up, so the compile target below is
// only where the *stable* half of the API surface is type-checked. Anything that
// changed across that range is reached through dev.belikhun.luna.core.fabric.compat,
// never linked directly, and the loom remap leaves those call sites alone.
val fabricApiVersion = libs.versions.fabricapi.get()

// src/main/java is the trunk both fabric builds compile; src/mc21/java is this
// build's half of the one class the two game lines cannot share. luna-core-mc26-fabric
// takes the trunk by reference and supplies the other half from its own sources.
sourceSets.named("main") {
	java.srcDir("src/mc21/java")
}

dependencies {
	minecraft("com.mojang:minecraft:${libs.versions.fabricminecraft.get()}")
	mappings(loom.officialMojangMappings())
	modCompileOnly("net.fabricmc:fabric-loader:${libs.versions.fabricloader.get()}")

	modCompileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	implementation(project(":luna-core-api"))
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)
	compileOnly(libs.adventure.serializer.gson)
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
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

// shadow runs first and stays in build/libs; remapJar takes its output and writes
// the deliverable, because a fabric mod has to be remapped after everything it
// carries is already inside it
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

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-core-fabric")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-core-fabric")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave an unremapped jar behind that no fabric server can load
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"))
}
