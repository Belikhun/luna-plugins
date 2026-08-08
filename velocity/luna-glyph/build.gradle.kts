import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Copy

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-velocity"))
	compileOnly(project(":luna-pack"))
	compileOnly(libs.velocity.api)
	compileOnly(libs.miniplaceholders.api)
	compileOnly(libs.tab.api)
	annotationProcessor(libs.velocity.api)
}

val generatedConstantsDir = layout.buildDirectory.dir("generated/sources/constants/java")
val pluginVersion = project.version.toString()

val generateBuildConstants by tasks.registering(Copy::class) {
	from(layout.projectDirectory.dir("src/main/templates"))
	into(generatedConstantsDir)
	rename("(.+)\\.java\\.tpl", "$1.java")
	expand("pluginVersion" to pluginVersion)
	inputs.property("pluginVersion", pluginVersion)
}

sourceSets {
	main {
		java.srcDir(generatedConstantsDir)
	}
}

tasks.withType<JavaCompile>().configureEach {
	dependsOn(generateBuildConstants)
}
