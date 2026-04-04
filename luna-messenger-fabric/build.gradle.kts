plugins {
	id("fabric-loom") version "1.10-SNAPSHOT"
}

import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.artifacts.VersionCatalogsExtension

dependencies {
	minecraft("com.mojang:minecraft:1.21.1")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:0.16.10")
	modImplementation("net.fabricmc.fabric-api:fabric-api:0.116.6+1.21.1")
	modImplementation(libs.fabric.placeholder.api)

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
val pluginBaseName = project.name.removePrefix("luna-").removeSuffix("-fabric")

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

val familyVariantTasks = familyIds.map { familyId ->
	val familyPascal = familyId.replaceFirstChar { it.uppercaseChar() }
	val sourceSetName = "${familyId}Implementation"
	val sourceSet = sourceSets.getByName(sourceSetName)

	tasks.register<Jar>("jar$familyPascal") {
		group = "build"
		description = "Build Fabric family jar for $familyId"
		dependsOn(tasks.named("classes"), tasks.named("${sourceSetName}Classes"))
		from(mainSourceSet.output)
		from(sourceSet.output)
		destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric/$familyId"))
		archiveBaseName.set("$pluginBaseName-fabric-$familyId")
		archiveVersion.set("")
		archiveClassifier.set("all")
	}

	tasks.register("shadowJar$familyPascal") {
		group = "build"
		description = "Alias family shadow task for $familyId"
		dependsOn(tasks.named("jar$familyPascal"))
	}

	tasks.register("verify$familyPascal") {
		group = "verification"
		description = "Compile/check Fabric family sources for $familyId"
		dependsOn(
			tasks.named("compile${familyPascal}ImplementationJava"),
			tasks.named("process${familyPascal}ImplementationResources")
		)
	}

	tasks.named("shadowJar") {
		dependsOn(tasks.named("jar$familyPascal"))
	}

	"verify$familyPascal"
}

tasks.register("verifyFabricFamilies") {
	group = "verification"
	description = "Run verification for all Fabric version families"
	dependsOn(familyVariantTasks.map { tasks.named(it) })
}
