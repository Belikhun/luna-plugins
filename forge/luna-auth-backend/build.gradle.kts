plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The restriction cage is core/luna-auth-backend-mc, the same sources the two
// fabric builds and the neoforge build compile; player-1x is this game line's
// half of the three calls 26.x re-spelled. Only the bootstrap below is this
// module's own, because the loaders differ in how they hand out events, not in
// what the cage does.
val lunaAuthMc = rootProject.layout.projectDirectory.dir("core/luna-auth-backend-mc/src")

sourceSets.named("main") {
	java.srcDir(lunaAuthMc.dir("main/java"))
	java.srcDir(lunaAuthMc.dir("player-1x/java"))
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
	archiveBaseName.set("luna-auth-backend-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-auth-backend-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}
