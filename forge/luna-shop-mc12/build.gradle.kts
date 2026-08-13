plugins {
	alias(libs.plugins.retrofuturagradle)
}

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The player shop on 1.12.2.
//
// Buying and selling, the daily trade limits and the item store all live in
// `luna-legacy-api` (dev.belikhun.luna.legacy.shop), generic over the player and
// item types; this module supplies the three seams that trunk is written
// against - `ShopItems`, `ShopInventory`, `ShopGameClock` - plus the GUI and the
// commands, which cannot be shared.
//
// **items.yml written here does not load on a modern backend.** An item is NBT,
// and 1.12.2's is not 1.21's; the shop's stock is per-backend data, unlike the
// cluster-wide config the server selector reads.
minecraft {
	mcVersion = "1.12.2"
	mcpMappingChannel = libs.versions.mcp112channel.get()
	mcpMappingVersion = libs.versions.mcp112version.get()
}

dependencies {
	compileOnly(project(":luna-legacy-api"))
	compileOnly(project(":luna-core-mc12-forge"))
}

tasks.named<ShadowJar>("shadowJar") {
	exclude("META-INF/MANIFEST.MF")

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-shop-mc12-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

tasks.named<com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar>("reobfJar") {
	inputJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-shop-mc12-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(tasks.named("reobfJar"))
}
