plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The trunk is the fabric module's, taken by reference exactly as the neoforge
// build takes it: everything under dev.belikhun.luna.vault.backend.mc is plain
// net.minecraft and compiles identically on all three loaders. Only the
// bootstrap is this module's own, and the exclude keeps the fabric one out.
val sharedTrunk = rootProject.layout.projectDirectory.dir("fabric/luna-vault-backend/src/main/java")

sourceSets.named("main") {
	java.srcDir(sharedTrunk)
	java.exclude("**/vault/backend/fabric/**")
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-vault-api"))
	compileOnly(project(":luna-core-forge"))
	compileOnly(libs.luckperms.api)
}

legacyForge {
	version = libs.versions.forge201.get()
}

// luna-vault-api is not a mod and never reaches the game on its own, so its classes
// ride inside this jar, exactly as they do in the fabric and neoforge builds.
tasks.named<ShadowJar>("shadowJar") {
	val apiJar = project(":luna-vault-api").tasks.named<Jar>("jar")
	dependsOn(apiJar)
	from(zipTree(apiJar.get().archiveFile.get().asFile))
	exclude("META-INF/MANIFEST.MF")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-vault-backend-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-vault-backend-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}
