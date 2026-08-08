import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.Configuration

// The 26.x build of luna-tab-bridge-fabric. The no-loom toolchain is the
// `-mc26-fabric` convention in the root build script.

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-mc26-fabric"))
	compileOnly(libs.luckperms.api)
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }
}
