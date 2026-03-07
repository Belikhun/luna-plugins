import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources

dependencies {
    compileOnly(libs.paper.api)
    implementation(libs.mariadb.jdbc)
    implementation(libs.mysql.jdbc)
    implementation(libs.postgresql.jdbc)
    implementation(libs.sqlite.jdbc)
}

val glyphMapOutputFile = layout.buildDirectory.file("generated/resources/glyph/font/glyph-widths.json")

val generateGlyphWidthMap by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate precomputed Minecraft glyph width map for runtime usage"

    dependsOn(tasks.named("compileJava"))

    mainClass.set("dev.belikhun.luna.core.tools.GlyphWidthMapGenerator")
    classpath = files(
        layout.buildDirectory.dir("classes/java/main"),
        configurations.getByName("compileClasspath")
    )
    args(
        layout.projectDirectory.dir("src/main/resources").asFile.absolutePath,
        glyphMapOutputFile.get().asFile.absolutePath
    )

    inputs.dir(layout.projectDirectory.dir("src/main/resources/font"))
    outputs.file(glyphMapOutputFile)
}

tasks.withType<ProcessResources>().configureEach {
    dependsOn(generateGlyphWidthMap)
    exclude {
        it.path.startsWith("font/") && it.path != "font/glyph-widths.json"
    }
    from(layout.buildDirectory.dir("generated/resources/glyph"))
}
