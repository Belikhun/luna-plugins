plugins {
	alias(libs.plugins.fabricloom)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.artifacts.Configuration
import org.gradle.jvm.tasks.Jar

// One jar serves every 1.21.x; see luna-core-fabric for how that is kept true.
// Nothing here is embedded: luna-core-api and luna-shop-api both arrive inside
// the lunacore jar, and fabric loads every mod through one class loader.
val fabricApiVersion = libs.versions.fabricapi.get()

dependencies {
	minecraft("com.mojang:minecraft:${libs.versions.fabricminecraft.get()}")
	mappings(loom.officialMojangMappings())
	modCompileOnly("net.fabricmc:fabric-loader:${libs.versions.fabricloader.get()}")

	modCompileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-shop-api"))
	compileOnly(project(":luna-vault-api"))
	compileOnly(project(":luna-core-fabric"))
}

// shadow runs first and stays in build/libs; remapJar takes its output and writes
// the deliverable, because a fabric mod has to be remapped after everything it
// carries is already inside it
// luna-shop-api is not a mod and never reaches the game on its own, so its
// classes ride inside this jar the way the paper build bundles them. luna-core-api
// is the exception: that one arrives inside the lunacore jar, which every luna
// fabric mod already depends on.
tasks.named<ShadowJar>("shadowJar") {
	val apiJar = project(":luna-shop-api").tasks.named<Jar>("jar")
	dependsOn(apiJar)
	configurations = project.provider { emptyList<Configuration>() }
	from(zipTree(apiJar.get().archiveFile.get().asFile))
	exclude("META-INF/MANIFEST.MF")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-shop-fabric")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-shop-fabric")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

// `gradlew shadowJar` is what the luna console drives, and on its own it would
// leave an unremapped jar behind that no fabric server can load
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"))
}
