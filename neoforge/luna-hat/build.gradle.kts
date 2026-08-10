plugins {
	alias(libs.plugins.moddevgradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

// The trunk is the fabric module's, taken by reference: everything under
// dev.belikhun.luna.hat.mc is plain net.minecraft and compiles identically on
// either loader, so it is written once. Only the bootstrap - the entry point and
// the event wiring - is this module's own, and the exclude below is what keeps
// the fabric one out.
val sharedTrunk = rootProject.layout.projectDirectory.dir("fabric/luna-hat/src/main/java")

sourceSets.named("main") {
	java.srcDir(sharedTrunk)
	java.exclude("**/hat/fabric/**")
}


// The helmet-slot mixin is per game line, not shared: 1.19-1.20.4 has no
// ArmorSlot class at all. See core/luna-core-mc/README.md for the convention;
// the sets live here because this module owns the hat trunk.
sourceSets.named("main") {
	java.srcDir(rootProject.layout.projectDirectory.dir("fabric/luna-hat/src/mixin-armorslot/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(libs.luckperms.api)
}

neoForge {
	version = libs.versions.neoforge.get()
}
