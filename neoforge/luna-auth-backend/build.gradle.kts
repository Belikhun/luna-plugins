plugins {
	alias(libs.plugins.moddevgradle)
}

// The restriction cage is core/luna-auth-backend-mc, the same sources the fabric
// and forge builds compile; player-1x is the 1.20-1.21 spelling of the three
// calls 26.x renamed, and 1.21.1 is on that side of the split. Only the
// bootstrap below is this module's own.
val lunaAuthMc = rootProject.layout.projectDirectory.dir("core/luna-auth-backend-mc/src")

sourceSets.named("main") {
	java.srcDir(lunaAuthMc.dir("main/java"))
	java.srcDir(lunaAuthMc.dir("player-1x/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(project(":luna-core-messaging"))
}

neoForge {
	version = libs.versions.neoforge.get()
}