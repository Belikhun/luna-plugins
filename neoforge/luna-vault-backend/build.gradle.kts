plugins {
	alias(libs.plugins.moddevgradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The trunk is the fabric module's, taken by reference: everything under
// dev.belikhun.luna.vault.backend.mc is plain net.minecraft and compiles identically on
// either loader, so it is written once. Only the bootstrap - the entry point and
// the event wiring - is this module's own, and the exclude below is what keeps
// the fabric one out.
val sharedTrunk = rootProject.layout.projectDirectory.dir("fabric/luna-vault-backend/src/main/java")

sourceSets.named("main") {
	java.srcDir(sharedTrunk)
	java.exclude("**/vault/backend/fabric/**")
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-vault-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(libs.luckperms.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}

// luna-vault-api is not a mod and never reaches the game on its own, so its
// classes ride inside this jar, exactly as they do in the fabric build.
tasks.named<ShadowJar>("shadowJar") {
	val apiJar = project(":luna-vault-api").tasks.named<Jar>("jar")
	dependsOn(apiJar)
	from(zipTree(apiJar.get().archiveFile.get().asFile))
	exclude("META-INF/MANIFEST.MF")
}
