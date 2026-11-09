// Paper half of luna-bpm: reports entity ids, worlds and tracking to the proxy.
// Standalone on purpose (no LunaCore dependency) so any Paper backend can run it.
dependencies {
	compileOnly(libs.paper.api)
}
