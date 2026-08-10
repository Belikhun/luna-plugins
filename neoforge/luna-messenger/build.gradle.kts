plugins {
	alias(libs.plugins.moddevgradle)
}

// The runtime is core/luna-messenger-mc, compiled by every mod loader; only the
// bootstrap below is this module's own.
val lunaMessengerMc = rootProject.layout.projectDirectory.dir("core/luna-messenger-mc/src")

sourceSets.named("main") {
	java.srcDir(lunaMessengerMc.dir("main/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(project(":luna-core-messaging"))
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
