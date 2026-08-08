import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
	alias(libs.plugins.moddevgradle)
}

val embeddedRabbitMqClient = configurations.detachedConfiguration(
	dependencies.create(libs.rabbitmq.client.get())
).apply {
	isTransitive = false
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-neoforge"))
	compileOnly(libs.luckperms.api)
	implementation(libs.rabbitmq.client)
}

neoForge {
	version = libs.versions.neoforge.get()
}

tasks.named<ShadowJar>("shadowJar") {
	from(embeddedRabbitMqClient.files.map { zipTree(it) })
	exclude("META-INF/MANIFEST.MF")
}
