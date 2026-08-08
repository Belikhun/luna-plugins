plugins {
	alias(libs.plugins.fabricloom)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

// src/main/java is the trunk both fabric builds compile; src/mc21/java is this
// build's half of the one class the two game lines cannot share (fabric-api
// renamed the play payload registries in the 26.x line).
val fabricApiVersion = libs.versions.fabricapi.get()

sourceSets.named("main") {
	java.srcDir("src/mc21/java")
}

val embeddedRabbitMqClient = configurations.detachedConfiguration(
	dependencies.create(libs.rabbitmq.client.get())
).apply {
	isTransitive = false
}

dependencies {
	minecraft("com.mojang:minecraft:${libs.versions.fabricminecraft.get()}")
	mappings(loom.officialMojangMappings())
	modCompileOnly("net.fabricmc:fabric-loader:${libs.versions.fabricloader.get()}")

	modCompileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-fabric"))
	compileOnly(libs.rabbitmq.client)
}

// shadow runs first and stays in build/libs; remapJar takes its output and writes
// the deliverable, because a fabric mod has to be remapped after everything it
// carries is already inside it
tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { listOf(embeddedRabbitMqClient) }
	exclude("META-INF/MANIFEST.MF")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-core-messaging-fabric")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-core-messaging-fabric")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave an unremapped jar behind that no fabric server can load
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"))
}
