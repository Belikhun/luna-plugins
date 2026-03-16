plugins {
    base
    id("com.gradleup.shadow") version "9.0.2" apply false
}

tasks.named<Delete>("clean") {
    delete(layout.projectDirectory.dir("output"))
}

subprojects {
    apply(plugin = "java")
    if (project.name != "luna-core-api") {
        apply(plugin = "com.gradleup.shadow")
    }

    group = "dev.belikhun.luna"
    version = "0.1.0-SNAPSHOT"
    val isApiModule = project.name == "luna-core-api"
    val isVelocityModule = project.name.endsWith("-velocity") || project.name == "luna-pack" || project.name == "luna-auth"
    val isPaperModule = project.name.endsWith("-paper") || (!isApiModule && !isVelocityModule)
    val platformTarget = when {
        isVelocityModule -> "velocity"
        isPaperModule -> "paper"
        else -> "api"
    }
    val moduleBaseName = when {
        isVelocityModule -> project.name.removeSuffix("-velocity")
        project.name.endsWith("-paper") -> project.name.removeSuffix("-paper")
        isApiModule -> project.name
        else -> project.name
    }

    val pluginVersion = version.toString()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
            maven("https://repo.lucko.me/")
        maven("https://repo.helpch.at/releases/")
        maven("https://repo.loohpjames.com/repository")
        maven("https://repo.william278.net/releases")
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
        filesMatching("velocity-plugin.json") {
            expand("version" to pluginVersion)
        }
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    if (!isApiModule) {
        tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar") {
            destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/$platformTarget"))
            archiveBaseName.set("${moduleBaseName}-$platformTarget")
            archiveVersion.set("")
        }
    }
}
