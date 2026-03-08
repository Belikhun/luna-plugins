dependencies {
	compileOnly(libs.paper.api)
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-paper"))
	implementation("org.apache.commons:commons-lang3:3.12.0")
}
