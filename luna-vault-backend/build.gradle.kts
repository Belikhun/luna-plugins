dependencies {
	implementation(project(":luna-vault-api"))
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-paper"))
	compileOnly(libs.paper.api)
	compileOnly(libs.placeholderapi)
	compileOnly(libs.vault.api)
}
