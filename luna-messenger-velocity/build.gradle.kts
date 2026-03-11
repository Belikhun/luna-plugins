import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.Copy
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-velocity"))
	compileOnly(libs.velocity.api)
	compileOnly(libs.miniplaceholders.api)
	compileOnly(libs.luckperms.api)
	annotationProcessor(libs.velocity.api)
	implementation(libs.jda) {
		// Voice codecs are not used by this plugin (text channel only).
		exclude(group = "club.minnced", module = "opus-java")
		exclude(group = "club.minnced", module = "opus-java-api")
		exclude(group = "club.minnced", module = "opus-java-natives")
		exclude(group = "net.java.dev.jna", module = "jna")
	}
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

tasks.named<ShadowJar>("shadowJar") {
	minimize()
}
