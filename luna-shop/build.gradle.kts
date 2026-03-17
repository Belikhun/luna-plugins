dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.vault.api)
    compileOnly(project(":luna-vault-api"))
    compileOnly(project(":luna-core-api"))
    compileOnly(project(":luna-core-paper"))
}
