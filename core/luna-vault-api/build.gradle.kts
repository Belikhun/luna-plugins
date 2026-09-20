dependencies {
	compileOnly(project(":luna-core-api"))

	// the ledger tests run against a real SQLite file: the transaction semantics
	// under test are the database's, not a mock's
	testImplementation(project(":luna-core-api"))
	testRuntimeOnly(libs.sqlite.jdbc)
}
