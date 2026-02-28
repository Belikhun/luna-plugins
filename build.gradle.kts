plugins {
    base
}

subprojects {
    apply(plugin = "java")

    group = "dev.belikhun.luna"
    version = "0.1.0-SNAPSHOT"

    val pluginVersion = version.toString()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        filesMatching("paper-plugin.yml") {
            expand("version" to pluginVersion)
        }
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }
}
