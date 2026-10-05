dependencies {
	compileOnly(libs.paper.api)
	compileOnly(libs.rabbitmq.client)
	compileOnly(libs.adventure.minimessage)
	compileOnly(libs.adventure.serializer.legacy)
	compileOnly(libs.luckperms.api)
	compileOnly(libs.spark.api)
	testRuntimeOnly(libs.adventure.minimessage)
	testRuntimeOnly(libs.adventure.serializer.legacy)
	testRuntimeOnly(libs.adventure.serializer.gson)
}
