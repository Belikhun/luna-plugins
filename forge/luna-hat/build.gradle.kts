plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The trunk is the fabric module's, taken by reference exactly as the neoforge
// build takes it: everything under dev.belikhun.luna.hat.mc is plain
// net.minecraft and compiles identically on all three loaders, so it is written
// once. Only the bootstrap is this module's own.
val sharedTrunk = rootProject.layout.projectDirectory.dir("fabric/luna-hat/src/main/java")

sourceSets.named("main") {
	java.srcDir(sharedTrunk)
	java.exclude("**/hat/fabric/**")
}


// The helmet-slot mixin is per game line, not shared: 1.19-1.20.4 has no
// ArmorSlot class at all. See core/luna-core-mc/README.md for the convention;
// the sets live here because this module owns the hat trunk.
sourceSets.named("main") {
	java.srcDir(rootProject.layout.projectDirectory.dir("fabric/luna-hat/src/mixin-inventoryslot/java"))
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-forge"))
	compileOnly(libs.luckperms.api)

	// The refmap and its mappings file are written by mixin's own annotation
	// processor. Naming the config is not enough to run it, and without it the
	// reobf step fails looking for lunahat.refmap.json.mappings.tsrg.
	annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
}

legacyForge {
	version = libs.versions.forge201.get()
}

// Unlike the 1.21 loaders, this line's mixin has to be remapped into SRG names
// and given a refmap; naming the config here is what wires both, and it also
// writes the manifest entry that makes forge load it.
val mixinRefmap = mixin.add(sourceSets.named("main").get(), "lunahat.refmap.json")

mixin {
	config("lunahat.mixins.json")
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-hat-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}

tasks.named<ShadowJar>("shadowJar") {
	// Forge 1.20.1 finds a mod's mixin configs through this manifest attribute
	// and nowhere else. The `[[mixins]]` block neoforge reads in its descriptor
	// is ignored here - silently: the config is simply never prepared, so the
	// mixin never applies and the log says nothing about it.
	manifest {
		attributes("MixinConfigs" to "lunahat.mixins.json")
	}

	// The refmap is written to build/mixin, which shadow knows nothing about, so
	// without this the jar ships a mixin config naming a refmap that is not in
	// it. Mixin needs the refmap to translate `mayPlace` into the SRG name the
	// game actually carries; nothing fails until the target class loads.
	from(mixinRefmap)

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-hat-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}
