plugins {
    base
    id("com.gradleup.shadow") version "9.0.2" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.gradleup.shadow")

    group = "dev.belikhun.luna"
    version = "0.1.0-SNAPSHOT"

    val pluginVersion = version.toString()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io")
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

    dependencies {
        add("testImplementation", "org.junit.jupiter:junit-jupiter-api:5.11.4")
        add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.11.4")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.11.4")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
        filesMatching("paper-plugin.yml") {
            expand("version" to pluginVersion)
        }
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar") {
        destinationDirectory.set(rootProject.layout.projectDirectory.dir("output"))
    }
}
