plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The trunk is the neoforge module's, taken by reference exactly as the other
// forge builds take theirs: everything under dev.belikhun.luna.tabbridge.mc is
// plain net.minecraft and compiles identically on both loaders. Only the
// bootstrap is this module's own, and the exclude keeps the neoforge one out.
val sharedTrunk = rootProject.layout.projectDirectory.dir("neoforge/luna-tab-bridge/src/main/java")

sourceSets.named("main") {
	java.srcDir(sharedTrunk)
	java.exclude("**/tabbridge/neoforge/**")
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
	archiveBaseName.set("luna-tab-bridge-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-tab-bridge-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}
