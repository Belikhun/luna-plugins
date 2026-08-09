import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.Configuration

// The 26.x build of luna-hat-fabric. The no-loom toolchain is the `-mc26-fabric`
// convention in the root build script.
//
// The mixin needs no refmap here: 26.x ships unobfuscated, so the names written
// in it are already the names the game runs under.

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-mc26-fabric"))
	compileOnly(libs.sponge.mixin)
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }
}
