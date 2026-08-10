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


// The helmet-slot mixin is per game line, not shared: 1.19-1.20.4 has no
// ArmorSlot class at all. See core/luna-core-mc/README.md for the convention;
// the sets live here because this module owns the hat trunk.
sourceSets.named("main") {
	java.srcDir(rootProject.layout.projectDirectory.dir("fabric/luna-hat/src/mixin-armorslot/java"))
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
	archiveBaseName.set("luna-hat-fabric")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-hat-fabric")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave an unremapped jar behind that no fabric server can load
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"))
}
