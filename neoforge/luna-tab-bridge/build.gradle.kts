plugins {
	alias(libs.plugins.moddevgradle)
}

// The runtime is shared with the forge and fabric bridges by reference: it is
// plain net.minecraft plus the core's placeholder engine, and compiles the same
// on every loader. Only the bootstrap under tabbridge/neoforge is this module's.
sourceSets.named("main") {
	java.srcDir(rootProject.layout.projectDirectory.dir("core/luna-tab-bridge-mc/src/main/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(project(":luna-core-messaging"))
	compileOnly(libs.luckperms.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
