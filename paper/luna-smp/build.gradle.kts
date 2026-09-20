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

	// Simple Upgrades is compiled against for the wireless power node, and no
	// maven repository carries the version the server runs (xenondevs' own
	// stops at 1.5-alpha.2), so the jar is taken off the GitHub release that
	// shipped it: release 57 is the Nova 0.22 set, the one on survival.
	ivy {
		url = uri("https://github.com/xenondevs/Nova-Addons/releases/download/")
		patternLayout {
			artifact("57/[module]-[revision]+Nova-0.22.jar")
		}
		metadataSources {
			artifact()
		}
		content {
			includeGroup("nova-addons")
		}
	}
}

dependencies {
	implementation(nova.nova)
	compileOnly("nova-addons:Simple_Upgrades:1.9.1")

	// Logistics is compiled against for the wire covers, which open a cable's
	// own side-configuration menu; same release, same reason as above
	compileOnly("nova-addons:Logistics:0.6.0")
	compileOnly(libs.paper.api)
	compileOnly(libs.vault.api)
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-paper"))
}

addon {
	name = "LunaSmp"

	// Nova bakes each block's backing state into its BLOCK_MODEL lookup during
	// the resource pack build and persists it; it only rebuilds when this
	// version changes. A stateSelector edit is invisible until it moves, so
	// bump it whenever a backing block changes.
	version = "0.2.0"
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

	// the wireless node stores its upgrades in Simple Upgrades' holder, so the
	// addon has to be up first; a Nova addon dependency, hence no stage
	dependency("Simple_Upgrades")

	// the wire covers reuse the cable's side-configuration menu and answer
	// with a cable's block id, so Logistics has to be up first
	dependency("Logistics")

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
