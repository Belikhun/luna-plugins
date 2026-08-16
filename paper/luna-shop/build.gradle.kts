dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.vault.api)
    compileOnly(project(":luna-vault-api"))
    implementation(project(":luna-shop-api"))
    compileOnly(project(":luna-core-api"))
    compileOnly(project(":luna-core-paper"))
}
