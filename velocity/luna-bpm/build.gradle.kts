import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// luna-bpm on the proxy: a network-wide server for the Better Player Model client
// mod (an open YSM fork). The model codecs under com.elfmcys / rip.ysm come from
// Zhop0rez/BetterPlayerModel's paper-plugin branch (MIT, see LICENSE in this folder).

repositories {
	// ImageStream (AVIF/WebP decoders used when a zipped model carries such textures)
	maven("https://jitpack.io")
}

dependencies {
	compileOnly(libs.velocity.api)
	annotationProcessor(libs.velocity.api)
	// Velocity ships netty at runtime; only the buffer API is used
	compileOnly("io.netty:netty-buffer:4.1.97.Final")

	implementation("org.yaml:snakeyaml:2.2")
	implementation("com.google.code.gson:gson:2.10.1")
	implementation("com.github.luben:zstd-jni:1.5.5-11")
	implementation("commons-io:commons-io:2.15.1")
	implementation("com.github.TartaricAlkaline:ImageStream:-SNAPSHOT")
	implementation("org.joml:joml:1.10.8")
	implementation("it.unimi.dsi:fastutil:8.5.14")
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	// the ported codec sources are not ours to restyle; keep their warnings quiet
	options.compilerArgs.add("-Xlint:none")
}

tasks.named<ShadowJar>("shadowJar") {
	val base = "dev.belikhun.luna.bpm.libs"
	relocate("org.yaml.snakeyaml", "$base.snakeyaml")
	relocate("com.google.gson", "$base.gson")
	// zstd-jni is NOT relocated: its JNI symbols are bound to the original package name
	relocate("org.apache.commons.io", "$base.commonsio")
	relocate("org.joml", "$base.joml")
	relocate("it.unimi.dsi.fastutil", "$base.fastutil")
	relocate("rip.ysm.imagestream", "$base.imagestream")
}
