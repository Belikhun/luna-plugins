import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.Configuration

// The 26.x build of luna-auth-backend-fabric. The no-loom toolchain is the
// `-mc26-fabric` convention in the root build script.
//
// The mixins this mod carries need no refmap here: 26.x ships unobfuscated, so
// the names written in them are already the names the game runs under. That is
// also why there is no remap step for this line at all.

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-mc26-fabric"))
	compileOnly(libs.sponge.mixin)
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }
}
