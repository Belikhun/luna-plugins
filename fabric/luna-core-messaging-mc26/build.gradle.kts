import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// The 26.x build of luna-core-messaging-fabric. The no-loom toolchain is the
// `-mc26-fabric` convention in the root build script; this module supplies the
// 26.x half of the payload registries from its own src/main/java.

val embeddedRabbitMqClient = configurations.detachedConfiguration(
	dependencies.create(libs.rabbitmq.client.get())
).apply {
	isTransitive = false
}

dependencies {
	compileOnly(project(":luna-core-api"))
	compileOnly(project(":luna-core-mc26-fabric"))
	compileOnly(libs.rabbitmq.client)
}

tasks.named<ShadowJar>("shadowJar") {
	configurations = project.provider { listOf(embeddedRabbitMqClient) }
	exclude("META-INF/MANIFEST.MF")
}
