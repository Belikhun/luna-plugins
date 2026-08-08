plugins {
	alias(libs.plugins.fabricloom)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.artifacts.Configuration

// One jar serves every game version from 1.20 up; see luna-core-fabric for how
// that is kept true. The bridge itself is protocol work and touches almost no
// game API - the world read goes through core/fabric/compat/WorldFacts.
val fabricApiVersion = libs.versions.fabricapi.get()

dependencies {
	minecraft("com.mojang:minecraft:${libs.versions.fabricminecraft.get()}")
	mappings(loom.officialMojangMappings())
	modCompileOnly("net.fabricmc:fabric-loader:${libs.versions.fabricloader.get()}")

	modCompileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-fabric"))
	compileOnly(libs.luckperms.api)
}

// shadow runs first and stays in build/libs; remapJar takes its output and writes
// the deliverable, because a fabric mod has to be remapped after everything it
// carries is already inside it
tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-tab-bridge-fabric")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-tab-bridge-fabric")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave an unremapped jar behind that no fabric server can load
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"))
}
