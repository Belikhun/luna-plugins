plugins {
	alias(libs.plugins.moddevgradle)
}

dependencies {
	implementation(project(":luna-core-api"))
	compileOnly(libs.luckperms.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
