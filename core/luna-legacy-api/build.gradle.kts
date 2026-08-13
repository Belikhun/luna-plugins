dependencies {
	// the AMQP client is class-file 52, so it runs on this line as-is; the
	// messaging mod is what actually ships it
	compileOnly(libs.rabbitmq.client)
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)

	// the platform jars shade adventure in; the tests have no platform, so they need
	// it on their own classpath to exercise the legacy rendering at all
	testImplementation(libs.adventure.minimessage)
	testImplementation(libs.adventure.serializer.legacy)
}
