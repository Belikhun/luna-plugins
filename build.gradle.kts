plugins {
    base
    id("com.gradleup.shadow") version "9.0.2" apply false
}

import org.gradle.api.artifacts.Configuration
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

tasks.named<Delete>("clean") {
    delete(layout.projectDirectory.dir("output"))
}

// Coordinates of the 26.x line, read here so the `subprojects` block below never
// touches a version-catalog accessor from inside another project's scope.
val mc26GameVersion = libs.versions.fabricminecraft26.get()
val mc26ServerSha1 = libs.versions.fabricminecraft26sha1.get()
val mc26LoaderVersion = libs.versions.fabricloader26.get()
val mc26ApiVersion = libs.versions.fabricapi26.get()

// The compile classpath every `-mc26-fabric` module builds against: the game's own
// server jar plus everything its bundler carries. It lives here, not per module,
// because it is the same 70 MB for all of them and downloading it once is the
// difference between one copy on disk and one per module.
val mc26MinecraftDir = layout.buildDirectory.dir("minecraft/$mc26GameVersion").get().asFile

val prepareMc26Minecraft = tasks.register("prepareMc26Minecraft") {
    description = "Download the $mc26GameVersion server bundle and unpack its classes and libraries."

    // the action reads only these locals: capturing a script property instead would
    // pull the build script itself into the action, which the configuration cache
    // cannot serialize
    val target = mc26MinecraftDir
    val version = mc26GameVersion
    val expectedSha1 = mc26ServerSha1

    // Mojang addresses its artifacts by content, so the sha1 is the URL's own last
    // path segment; checking it is what makes a build-time download trustworthy
    val url = "https://piston-data.mojang.com/v1/objects/$expectedSha1/server.jar"

    outputs.dir(target)

    doLast {
        target.deleteRecursively()
        target.mkdirs()

        val bundle = File(target, "bundle.jar")

        URI(url).toURL().openStream().use { input ->
            bundle.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val digest = MessageDigest.getInstance("SHA-1")

        bundle.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)

            while (true) {
                val read = input.read(buffer)

                if (read < 0) {
                    break
                }

                digest.update(buffer, 0, read)
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }

        if (actual != expectedSha1) {
            throw GradleException("server bundle for $version hashed $actual, expected $expectedSha1")
        }

        // the bundler holds the real server jar under versions/ and every library it
        // links against under libraries/; flattening both gives a complete, exact
        // compile classpath without resolving a single coordinate
        ZipFile(bundle).use { zip ->
            zip.entries()
                .asSequence()
                .filter {
                    it.name.endsWith(".jar") &&
                        (it.name.startsWith("META-INF/libraries/") || it.name.startsWith("META-INF/versions/"))
                }
                .forEach { entry ->
                    val unpacked = File(target, entry.name.substringAfterLast('/'))

                    zip.getInputStream(entry).use { input ->
                        unpacked.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
        }

        bundle.delete()
    }
}

subprojects {
    apply(plugin = "java")
    if (!project.name.endsWith("-api")) {
        apply(plugin = "com.gradleup.shadow")
    }

    group = "dev.belikhun.luna"
    version = "0.1.0-SNAPSHOT"
    val isApiModule = project.name.endsWith("-api")
    val isNeoForgeModule = project.name.endsWith("-neoforge") || project.name == "luna-core-messaging"
    val isFabricModule = project.name.endsWith("-fabric")
    // classic forge, as opposed to neoforge; the two are separate loaders with
    // separate metadata, and `-neoforge` is matched first so it never lands here
    val isForgeModule = !project.name.endsWith("-neoforge") && project.name.endsWith("-forge")
    // The 1.12.2 build of a forge module. Still a forge module for every purpose the
    // artifact has - it pools and deploys as one - but it compiles against a different
    // era entirely: MCP names, legacy FML, and Java 8 bytecode. Only the toolchain
    // branches below care about the difference.
    val isLegacyForgeModule = project.name.endsWith("-mc12-forge")
    // The Java 8 half of the api surface, compiled for that same line.
    val isLegacyApiModule = project.name == "luna-legacy-api"
    // Anything that has to load on a 1.12.2 server, whose JVM is 8.
    val isLegacyModule = isLegacyForgeModule || isLegacyApiModule
    // the second build of a fabric module, for the game line that ships unobfuscated
    val isMc26FabricModule = project.name.endsWith("-mc26-fabric")
    val isVelocityModule = project.name.endsWith("-velocity") || project.name == "luna-pack" || project.name == "luna-auth" || project.name == "luna-vault" || project.name == "luna-glyph"
    val isPaperModule = project.name.endsWith("-paper") || (!isApiModule && !isVelocityModule && !isNeoForgeModule && !isForgeModule && !isFabricModule)
    val platformTarget = when {
        isNeoForgeModule -> "neoforge"
        isForgeModule -> "forge"
        isFabricModule -> "fabric"
        isVelocityModule -> "velocity"
        isPaperModule -> "paper"
        else -> "api"
    }
    val moduleBaseName = when {
        isVelocityModule -> project.name.removeSuffix("-velocity")
        isNeoForgeModule && project.name.endsWith("-neoforge") -> project.name.removeSuffix("-neoforge")
        isForgeModule -> project.name.removeSuffix("-forge")
        isFabricModule -> project.name.removeSuffix("-fabric")
        project.name.endsWith("-paper") -> project.name.removeSuffix("-paper")
        isApiModule -> project.name
        else -> project.name
    }

    val pluginVersion = version.toString()

    repositories {
        mavenCentral()
        maven("https://maven.maxhenkel.de/repository/public")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.lucko.me/")
        maven("https://repo.helpch.at/releases/")
        maven("https://repo.loohpjames.com/repository")
        maven("https://repo.william278.net/releases")
        maven("https://maven.neoforged.net/releases")
        maven("https://repo.codemc.org/repository/maven-public/")
        maven("https://jitpack.io")

        if (isMc26FabricModule) {
            // fabric-loader and fabric-api are published here only; the 1.21 modules
            // get this repository from loom, which these builds do not apply
            maven("https://maven.fabricmc.net/")
        }
    }

    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(if (isLegacyModule) 8 else 21)

            if (isLegacyModule) {
                // Azul is the vendor with the widest platform coverage still shipping
                // a Java 8 toolchain, and it is what the host already runs for 21/25
                vendor = JvmVendorSpec.AZUL
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // The api jars are consumed by every platform, and the oldest JVM in the
        // fleet sets their floor: a Forge 1.19.2/1.20.1 server runs Java 17 and
        // cannot load class-file 65. 17-bytecode loads fine everywhere newer, and
        // the forge modules themselves run on that same JVM.
        //
        // The 1.12.2 line sits below all of it: a stock server of that era runs Java
        // 8, so its modules emit class-file 52. That floor is also why they cannot
        // consume luna-core-api and have luna-legacy-api instead.
        //
        // They get there by toolchain rather than by flag. `--release` arrived in
        // Java 9, so setting it at all - even to 8 - makes a Java 8 javac fail with
        // `invalid flag: --release`, and that includes the compile tasks RFG adds to
        // the project. A Java 8 compiler emits class-file 52 with nothing asked of
        // it, which is the whole point of pinning the toolchain instead.
        if (!isLegacyModule) {
            options.release = if (isApiModule || isForgeModule) 17 else 21
        }
    }

    // The 21 attribute exists to let paper-api resolve while emitting 17. The legacy
    // api has no paper on its classpath and emits 8, so asking for 21 there would
    // only make gradle reject Java 8 artifacts it is entitled to use.
    if (isApiModule && !isLegacyApiModule) {
        // Emitting 17 makes gradle look for dependencies a 17 runtime could use, and
        // paper-api publishes itself as 21-only, so resolution fails before javac is
        // even reached. javac has no such problem: `--release` governs which *JDK*
        // API is visible, and a class file on the classpath is read whatever its
        // version. So the classpath keeps asking for 21 while the output stays 17.
        //
        // What that costs is real but already the arrangement: the handful of
        // paper-facing classes in luna-core-api ship inside every mod jar and would
        // fail to link if a mod platform ever touched them. Nothing does - they are
        // reached through the paper bootstrap only.
        configurations.named("compileClasspath") {
            attributes {
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
            }
        }
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
        filesMatching("META-INF/neoforge.mods.toml") {
            expand("version" to pluginVersion)
        }
        // classic forge reads the same descriptor under its pre-fork name
        filesMatching("META-INF/mods.toml") {
            expand("version" to pluginVersion)
        }
        // and 1.12.2, before mods.toml existed at all, reads a json array at the root
        filesMatching("mcmod.info") {
            expand("version" to pluginVersion)
        }
        filesMatching("velocity-plugin.json") {
            expand("version" to pluginVersion)
        }
        filesMatching("fabric.mod.json") {
            expand("version" to pluginVersion)
        }
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    // The 26.x build of a fabric module: same sources, different namespace.
    //
    // From 26.1 Mojang ships the server unobfuscated, so fabric publishes intermediary
    // as the empty 0.0.0 mapping and mods are expected to link the game's real names.
    // That is why there is no loom here and no remap step: an intermediary-remapped jar
    // cannot run on 26.x, and a jar built against real names needs no remapping to.
    // The classpath is the game's own server jar plus everything its bundler carries,
    // which is both exact and immune to a mapping publication that does not exist.
    //
    // Nothing in src/ is duplicated - the sibling's sources are this module's too, by
    // reference - so a fix lands on both lines at once and the two builds cannot drift.
    if (isMc26FabricModule) {
        val sharedDir = project(":${project.name.removeSuffix("-mc26-fabric")}-fabric").projectDir

        extensions.configure<SourceSetContainer>("sourceSets") {
            named("main") {
                java.srcDir(File(sharedDir, "src/main/java"))

                // The platform-free UI toolkit the 1.21 sibling also compiles, plus
                // the compat sets for this line: 26.x renamed the click enum to
                // ContainerInput and ResourceLocation to Identifier. See
                // core/luna-core-mc/README.md for why those are sets and not copies.
                // the hat mixin is per game line; 26.x spells it the same way
                // 1.21 does, so it takes the sibling's named-class set
                val mixinSet = File(sharedDir, "src/mixin-armorslot/java")

                if (mixinSet.isDirectory) {
                    java.srcDir(mixinSet)
                }

                if (project.name == "luna-countdown-mc26-fabric") {
                    java.srcDir(rootProject.layout.projectDirectory.dir("core/luna-countdown-mc/src/main/java"))
                }

                if (project.name == "luna-messenger-mc26-fabric") {
                    java.srcDir(rootProject.layout.projectDirectory.dir("core/luna-messenger-mc/src/main/java"))
                }

                if (project.name == "luna-auth-backend-mc26-fabric") {
                    val lunaAuthMc = rootProject.layout.projectDirectory.dir("core/luna-auth-backend-mc/src")

                    java.srcDir(lunaAuthMc.dir("main/java"))
                    java.srcDir(lunaAuthMc.dir("player-26/java"))
                }

                if (project.name == "luna-core-mc26-fabric") {
                    val lunaCoreMc = rootProject.layout.projectDirectory.dir("core/luna-core-mc/src")

                    java.srcDir(lunaCoreMc.dir("main/java"))
                    java.srcDir(lunaCoreMc.dir("player-26/java"))
                    java.srcDir(lunaCoreMc.dir("menu-containerinput/java"))
                    java.srcDir(lunaCoreMc.dir("registry-identifier/java"))
                    java.srcDir(lunaCoreMc.dir("decor-components/java"))
                    java.srcDir(lunaCoreMc.dir("itemio-codec/java"))
                }
            }
        }

        dependencies {
            add("compileOnly", files(fileTree(mc26MinecraftDir) { include("*.jar") }).builtBy(prepareMc26Minecraft))
            add("compileOnly", "net.fabricmc:fabric-loader:$mc26LoaderVersion")

            // fabric-api is a hard dependency of every luna fabric mod, so the whole
            // artifact is on the classpath here as it is in the 1.21 modules. Using a
            // piece that line does not have still fails, because the same sources are
            // compiled there against that line's own fabric-api.
            add("compileOnly", "net.fabricmc.fabric-api:fabric-api:$mc26ApiVersion")
        }

        // 26.x classes are class-file version 69, which only a JDK 25 javac can read.
        // The bytecode emitted stays at 21, matching every other module: the game
        // requiring a newer runtime says nothing about what this mod compiles to.
        extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
            from(File(sharedDir, "src/main/resources")) {
                // this module declares its own game range and its own mod descriptor
                exclude("fabric.mod.json", "luna-plugin.json")
            }
        }
    }

    if (!isApiModule) {
        tasks.named<ShadowJar>("shadowJar") {
            destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/$platformTarget"))
            archiveBaseName.set("${moduleBaseName}-$platformTarget")
            archiveVersion.set("")

            if (isNeoForgeModule || isForgeModule) {
                configurations = project.provider { emptyList<Configuration>() }
            }
        }
    }
}
