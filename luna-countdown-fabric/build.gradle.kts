plugins {
	id("fabric-loom") version "1.13.6"
}

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.artifacts.VersionCatalogsExtension
import net.fabricmc.loom.task.RemapJarTask

dependencies {
	minecraft("com.mojang:minecraft:1.21.11")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:0.18.2")
	modImplementation("net.fabricmc.fabric-api:fabric-api:0.139.4+1.21.11")
	modCompileOnly("me.lucko:fabric-permissions-api:0.6.1")

	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-fabric"))
	testImplementation(project(":luna-core-api"))
	testImplementation(project(":luna-core-fabric"))
}

val familyIds = listOf("mc1165", "mc1182", "mc119x", "mc1201", "mc121x")
val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
val familyJavaRelease = mapOf(
	"mc1165" to 8,
	"mc1182" to 17,
	"mc119x" to 17,
	"mc1201" to 17,
	"mc121x" to 21
)
val mainSourceSet = sourceSets.named("main").get()
val moduleArchiveBaseName = project.name.removeSuffix("-fabric")

sourceSets {
	familyIds.forEach { familyId ->
		create("${familyId}Implementation") {
			java.srcDir("src/$familyId/java")
			resources.srcDir("src/$familyId/resources")
			compileClasspath += mainSourceSet.output + configurations.getByName("compileClasspath")
			runtimeClasspath += output + compileClasspath
		}
	}
}

val familySourceSets = familyIds.map { familyId -> sourceSets.named("${familyId}Implementation").get() }

dependencies {
	familyIds.forEach { familyId ->
		val sourceSetName = "${familyId}Implementation"
		add("${sourceSetName}CompileOnly", versionCatalog.findLibrary("fabric-loader-$familyId").get())
		add("${sourceSetName}CompileOnly", versionCatalog.findLibrary("fabric-api-$familyId").get())
	}
}

tasks.withType<JavaCompile>().configureEach {
	familyJavaRelease.forEach { (familyId, releaseVersion) ->
		val familyPascal = familyId.replaceFirstChar { it.uppercaseChar() }
		if (name.contains("${familyPascal}Implementation")) {
			options.release = releaseVersion
		}
	}
}

tasks.named<Jar>("jar") {
	dependsOn(familySourceSets.map { tasks.named(it.classesTaskName) })
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from(familySourceSets.map { it.output })
}

val familyVerificationTasks = familyIds.map { familyId ->
	val familyPascal = familyId.replaceFirstChar { it.uppercaseChar() }
	val sourceSetName = "${familyId}Implementation"

	tasks.register("verify$familyPascal") {
		group = "verification"
		description = "Compile/check Fabric family sources for $familyId"
		dependsOn(
			tasks.named("compile${familyPascal}ImplementationJava"),
			tasks.named("process${familyPascal}ImplementationResources")
		)
	}

	"verify$familyPascal"
}

tasks.named<RemapJarTask>("remapJar") {
	dependsOn(tasks.named("jar"))
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("$moduleArchiveBaseName-fabric")
	archiveVersion.set("")
	archiveClassifier.set("")
}

tasks.register("verifyFabricFamilies") {
	group = "verification"
	description = "Run verification for all Fabric version families"
	dependsOn(familyVerificationTasks.map { tasks.named(it) })
}
