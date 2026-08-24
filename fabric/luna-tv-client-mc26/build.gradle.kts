import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.artifacts.Configuration

// The 26.x build of luna-tv-client. The no-loom toolchain is the `-mc26-fabric`
// convention in the root build script, which also puts the sibling's shared
// sources on this module's source path; only the four classes that name game
// types differently live here, plus the mixin.
//
// The mixin needs no refmap: 26.x ships unobfuscated, so the names written in it
// are already the names the game runs under.

// This is the one mc26 module that draws, and the shared mc26 classpath cannot
// serve it: that is the server bundle, which has no LevelRenderer, no RenderTypes
// and no texture manager. The client jar is fetched here, for this module alone.
val clientSha1 = libs.versions.fabricminecraft26clientsha1.get()
val clientDir = layout.buildDirectory.dir("minecraft-client").get().asFile

val prepareMc26Client = tasks.register("prepareMc26Client") {
	description = "Download the 26.x client jar this module compiles its renderer against."

	val target = clientDir
	val expectedSha1 = clientSha1

	// Mojang addresses its artifacts by content, so the sha1 is the URL's own last
	// path segment; checking it is what makes a build-time download trustworthy
	val url = "https://piston-data.mojang.com/v1/objects/$expectedSha1/client.jar"

	outputs.dir(target)

	doLast {
		target.mkdirs()

		val jar = File(target, "client.jar")

		URI(url).toURL().openStream().use { input ->
			jar.outputStream().use { output ->
				input.copyTo(output)
			}
		}

		val digest = MessageDigest.getInstance("SHA-1")
		val actual = digest.digest(jar.readBytes()).joinToString("") { "%02x".format(it) }

		check(actual == expectedSha1) {
			"client jar sha1 mismatch: expected $expectedSha1, got $actual"
		}
	}
}

// The `-mc26-fabric` convention in the root build script shares the sibling's
// java sources and nothing else, so the decoder has to be pointed at from here
// as well or this jar would ship without one.
sourceSets {
	named("main") {
		resources.srcDir("../luna-tv-client/native/dist")
	}
}

dependencies {
	compileOnly(files(File(clientDir, "client.jar")).builtBy(prepareMc26Client))
	compileOnly(libs.sponge.mixin)
	// MemoryUtil, for copying a decoded frame into the texture's own memory. The
	// server bundle carries no lwjgl, being a server.
	compileOnly("org.lwjgl:lwjgl:3.3.3")
	compileOnly("org.lwjgl:lwjgl-glfw:3.3.3")
	compileOnly("org.lwjgl:lwjgl-openal:3.3.3")
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }
}
