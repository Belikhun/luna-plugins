import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.Configuration
import org.gradle.jvm.tasks.Jar

// The 26.x build of luna-shop-fabric. The no-loom toolchain is the
// `-mc26-fabric` convention in the root build script; this module's own sources
// are the sibling's, taken by reference.

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-shop-api"))
	compileOnly(project(":luna-vault-api"))
	compileOnly(project(":luna-core-mc26-fabric"))
}

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
}
