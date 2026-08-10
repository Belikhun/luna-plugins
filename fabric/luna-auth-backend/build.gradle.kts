plugins {
	alias(libs.plugins.fabricloom)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.artifacts.Configuration

// One jar serves every 1.21.x; see luna-core-fabric for how that is kept true.
// Nothing here is embedded: luna-core-api arrives inside the lunacore jar, and
// fabric loads every mod through one class loader.
val fabricApiVersion = libs.versions.fabricapi.get()

// src/main/java is the trunk both fabric builds compile; src/mc21/java is this
// build's half of the one callback the two game lines cannot share.
// luna-auth-backend-mc26-fabric takes the trunk by reference and supplies the
// other half from its own sources.
// The restriction cage itself lives in core/luna-auth-backend-mc and is compiled
// by every mod loader; player-1x is this game line's half of the three calls 26.x
// re-spelled. See core/luna-core-mc/README.md for the compat-set convention.
val lunaAuthMc = rootProject.layout.projectDirectory.dir("core/luna-auth-backend-mc/src")

sourceSets.named("main") {
	java.srcDir("src/mc21/java")
	java.srcDir(lunaAuthMc.dir("main/java"))
	java.srcDir(lunaAuthMc.dir("player-1x/java"))
}

dependencies {
	minecraft("com.mojang:minecraft:${libs.versions.fabricminecraft.get()}")
	mappings(loom.officialMojangMappings())
	modCompileOnly("net.fabricmc:fabric-loader:${libs.versions.fabricloader.get()}")

	modCompileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-fabric"))
}

// shadow runs first and stays in build/libs; remapJar takes its output and writes
// the deliverable, because a fabric mod has to be remapped after everything it
// carries is already inside it - and this one carries mixins, whose refmap loom
// writes during that same step
tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-auth-backend-fabric")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-auth-backend-fabric")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave an unremapped jar behind that no fabric server can load
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"))
}
