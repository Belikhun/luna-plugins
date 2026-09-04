plugins {
	alias(libs.plugins.fabricloom)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.artifacts.Configuration

// Compiled against 1.21.10 and meant to run on it and later.
//
// The floor is Fabric's, not ours: the world render events were deleted in the
// 1.21.9 port and reinstated under a new package in 1.21.10, so no single build
// can hook a frame on both sides of that. The ceiling is open because nothing
// here touches Minecraft's rendering classes. 1.21.5 replaced core shader JSONs
// with RenderPipeline and 1.21.6 took RenderType and VertexConsumer out of most
// call paths, so a mod built on those draws on one version and no other; this
// one talks to OpenGL through LWJGL, which has not moved, and to Minecraft only
// for a frame callback, the camera and the projection.
val fabricApiVersion = libs.versions.fabricapitv.get()

sourceSets {
	named("main") {
		// The 1.21.x half of the compat split; 26.x compiles its own copy of these
		// four classes against a game that names them differently.
		java.srcDir("src/mc121/java")

		// The bundled H.264 decoder, built by native/build-ffmpeg.sh. A second
		// resource root rather than a copy under src/, because it is a build
		// artifact: it is six megabytes, it is regenerated, and it has no
		// business in the source tree.
		resources.srcDir("native/dist")
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${libs.versions.fabricminecrafttv.get()}")
	mappings(loom.officialMojangMappings())
	modCompileOnly("net.fabricmc:fabric-loader:${libs.versions.fabricloadertv.get()}")
	modCompileOnly("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
	// The input layer chains Minecraft's own GLFW callbacks, which is the one
	// input API both game lines spell the same way. Loom puts lwjgl's core on the
	// classpath but not its glfw bindings.
	compileOnly("org.lwjgl:lwjgl-glfw:3.3.3")
	// The screens mix their own sound into the game's OpenAL context. Loom puts
	// lwjgl's core on the classpath but not its openal bindings either.
	compileOnly("org.lwjgl:lwjgl-openal:3.3.3")
}

// The mod ships in two variants, built from one source set.
//
// The ffmpeg build carries the bundled H.264 decoder and streams H.264; the
// mjpeg build leaves the binary out, which takes the jar from two megabytes to
// ninety kilobytes and pins it to the stream of whole JPEGs. Each jar is stamped with its own marker
// so NativeFfmpeg can name the build in the log, rather than leaving a player
// to deduce it from behaviour. Two variants of one source set rather than two
// source sets, because nothing about the code differs: the only difference is
// whether a resource is in the jar.
fun variantMarker(variant: String) = resources.text.fromString(variant + "\n").asFile()

/** Everything under here is the bundled decoder, and the whole mjpeg/ffmpeg split. */
val nativeDecoder = "lunatv/native/**"

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { emptyList<Configuration>() }

	from(variantMarker("ffmpeg")) {
		into("lunatv")
		rename { "variant" }
	}

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-tv-client")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<RemapJarTask>("remapJar") {
	inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-tv-client")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

val mjpegShadowJar = tasks.register<ShadowJar>("mjpegShadowJar") {
	configurations = project.provider { emptyList<Configuration>() }

	// registered rather than derived from the main task: a ShadowJar built by
	// hand is fed the source set itself, where the plugin's own task has that
	// wired for it
	from(sourceSets["main"].output)
	exclude(nativeDecoder)

	from(variantMarker("mjpeg")) {
		into("lunatv")
		rename { "variant" }
	}

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-tv-client-mjpeg")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

val mjpegRemapJar = tasks.register<RemapJarTask>("mjpegRemapJar") {
	inputFile.set(mjpegShadowJar.flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/fabric"))
	archiveBaseName.set("luna-tv-client-mjpeg")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

mjpegShadowJar.configure {
	finalizedBy(mjpegRemapJar)
}

// Both variants come out of one build, always in step: a jar pair where only
// one half was rebuilt is the kind of mismatch nobody notices until a player
// reports behaviour the source no longer has.
tasks.named("shadowJar") {
	finalizedBy(tasks.named("remapJar"), mjpegShadowJar)
}
