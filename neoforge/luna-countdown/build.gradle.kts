plugins {
	alias(libs.plugins.moddevgradle)
}

// The runtime is core/luna-countdown-mc, compiled by every mod loader; only the
// bootstrap below is this module's own.
val lunaCountdownMc = rootProject.layout.projectDirectory.dir("core/luna-countdown-mc/src")

sourceSets.named("main") {
	java.srcDir(lunaCountdownMc.dir("main/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(libs.luckperms.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
