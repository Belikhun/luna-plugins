import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The 26.x build of luna-core-fabric. The whole no-loom toolchain - the game
// bundle download, the shared source tree, the toolchain and the shared resources
// - is the `-mc26-fabric` convention in the root build script; this file only
// carries what is this module's own.

dependencies {
	implementation(project(":luna-core-api"))
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)
	compileOnly(libs.adventure.serializer.gson)
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
}

val embeddedAdventureMiniMessage = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.minimessage.get())
)

val embeddedAdventureSerializerLegacy = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.serializer.legacy.get())
)

val embeddedAdventureSerializerGson = configurations.detachedConfiguration(
	dependencies.create(libs.adventure.serializer.gson.get())
)

val embeddedSnakeYaml = configurations.detachedConfiguration(
	dependencies.create("org.yaml:snakeyaml:2.2")
)

tasks.named<ShadowJar>("shadowJar") {
	val coreApiJar = project(":luna-core-api").tasks.named<Jar>("jar")
	dependsOn(coreApiJar)
	configurations = project.provider {
		listOf(
			embeddedAdventureMiniMessage,
			embeddedAdventureSerializerLegacy,
			embeddedAdventureSerializerGson,
			embeddedSnakeYaml
		)
	}
	from(zipTree(coreApiJar.get().archiveFile.get().asFile))
	mergeServiceFiles()
	relocate("net.kyori.adventure", "dev.belikhun.luna.shadow.net.kyori.adventure")
	relocate("net.kyori.examination", "dev.belikhun.luna.shadow.net.kyori.examination")
	relocate("net.kyori.option", "dev.belikhun.luna.shadow.net.kyori.option")
	relocate("org.yaml.snakeyaml", "dev.belikhun.luna.shadow.snakeyaml")
	exclude("META-INF/MANIFEST.MF")
	exclude("com/google/gson/**")
	exclude("META-INF/maven/com.google.code.gson/**")
}
