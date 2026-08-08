import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.Configuration

// The 26.x build of luna-countdown-fabric. The no-loom toolchain - the game
// bundle download, the shared source tree, the toolchain and the shared
// resources - is the `-mc26-fabric` convention in the root build script.

dependencies {
	compileOnly(project(":luna-core-api"))

	// the 26.x core, not the 1.21 one: both publish the same class names, but only
	// this one's signatures were checked against the game this build targets
	compileOnly(project(":luna-core-mc26-fabric"))
	compileOnly(libs.luckperms.api)
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }
}
