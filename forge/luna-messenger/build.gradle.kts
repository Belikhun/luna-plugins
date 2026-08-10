plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The runtime is core/luna-messenger-mc, compiled by every mod loader; only the
// bootstrap below is this module's own.
val lunaMessengerMc = rootProject.layout.projectDirectory.dir("core/luna-messenger-mc/src")

sourceSets.named("main") {
	java.srcDir(lunaMessengerMc.dir("main/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-forge"))
	compileOnly(project(":luna-core-messaging-forge"))
}

legacyForge {
	version = libs.versions.forge201.get()
}

tasks.named<ShadowJar>("shadowJar") {
	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-messenger-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-messenger-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}
