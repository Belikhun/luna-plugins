import xyz.xenondevs.novagradle.task.PluginDependency

// LunaSmp is both a regular Paper plugin (pluginMain, all the existing Java
// modules) and a Nova addon (main, the furniture + custom items). The nova
// gradle plugin generates paper-plugin.yml and nova-addon.yml, so this module
// has no static descriptor of its own.
plugins {
	alias(nova.plugins.kotlin)
	alias(nova.plugins.nova)
}

// the root's subprojects block declares project-level repositories, which
// take precedence over settings-level ones; nova's artifacts live elsewhere
repositories {
	maven("https://repo.xenondevs.xyz/releases/")
}

dependencies {
	implementation(nova.nova)
	compileOnly(libs.paper.api)
	compileOnly(libs.vault.api)
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-paper"))
}

addon {
	name = "LunaSmp"
	version = project.version.toString()
	main = "dev.belikhun.luna.smp.LunaSmp"
	pluginMain = "dev.belikhun.luna.smp.LunaSmpPlugin"
	authors = listOf("Belikhun")
	description = "Plugin SMP tổng hợp cho Luna survival server."

	// the same dependency set the old static paper-plugin.yml declared. Server
	// stage only: neither plugin has a bootstrapper, so naming them at the
	// bootstrap stage is an unknown-dependency error before the server exists
	dependency("LunaCore", PluginDependency.Stage.SERVER, PluginDependency.Load.BEFORE, true, true)
	dependency("Vault", PluginDependency.Stage.SERVER, PluginDependency.Load.BEFORE, true, false)

	// the economy provider must have registered before onEnable asks Vault for
	// one; optional, so a backend without luna's economy still boots
	dependency("LunaVaultBackend", PluginDependency.Stage.SERVER, PluginDependency.Load.BEFORE, false, false)

	destination = rootProject.layout.projectDirectory.dir("output/paper").asFile
	fileName = "luna-smp-paper.jar"
}

// the addon jar replaces the shadow jar as this module's pooled artifact;
// both would write output/paper/luna-smp-paper.jar, so only one may run
tasks.named("shadowJar") {
	enabled = false
}

tasks.named("build") {
	dependsOn("addonJar")
}
