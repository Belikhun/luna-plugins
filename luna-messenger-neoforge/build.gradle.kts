plugins {
	alias(libs.plugins.moddevgradle)
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(project(":luna-core-messaging"))
	compileOnly(project(":luna-tab-bridge-neoforge"))
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
