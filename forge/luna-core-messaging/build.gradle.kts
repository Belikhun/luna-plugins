import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	alias(libs.plugins.moddevgradle.legacy)
}

// The trunk is the neoforge module's, taken by reference: everything under
// dev.belikhun.luna.core.messaging.mc is the bus, the AMQP transports and the
// channel provider, none of which name a loader. Only the bootstrap and the
// payload transport are this module's own.
val sharedTrunk = rootProject.layout.projectDirectory.dir("neoforge/luna-core-messaging/src/main/java")

sourceSets.named("main") {
	java.srcDir(sharedTrunk)
	java.exclude("**/core/messaging/neoforge/**")
}

val embeddedRabbitMqClient = configurations.detachedConfiguration(
	dependencies.create(libs.rabbitmq.client.get())
).apply {
	isTransitive = false
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-forge"))
	compileOnly(libs.luckperms.api)
	implementation(libs.rabbitmq.client)
}

legacyForge {
	version = libs.versions.forge201.get()
}

tasks.named<ShadowJar>("shadowJar") {
	from(embeddedRabbitMqClient.files.map { zipTree(it) })

	destinationDirectory.set(layout.buildDirectory.dir("libs"))
	archiveBaseName.set("luna-core-messaging-forge")
	archiveClassifier.set("shaded")
	archiveVersion.set("")
}

val reobfShadowJar = obfuscation.reobfuscate(tasks.named<ShadowJar>("shadowJar"), sourceSets.named("main").get()) {
	destinationDirectory.set(rootProject.layout.projectDirectory.dir("output/forge"))
	archiveBaseName.set("luna-core-messaging-forge")
	archiveClassifier.set("all")
	archiveVersion.set("")
}

tasks.named("shadowJar") {
	finalizedBy(reobfShadowJar)
}
