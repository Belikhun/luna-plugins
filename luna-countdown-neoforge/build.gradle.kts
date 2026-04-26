plugins {
	alias(libs.plugins.moddevgradle)
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(libs.luckperms.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
