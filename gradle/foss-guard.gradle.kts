// Applied by :app. Kept in its own file because it has to name the proprietary groups it forbids (the CI text check skips this file).
// The `foss` flavor must never carry Google Play Services, Firebase or any proprietary SDK (F-Droid; CLAUDE.md). Fails the
// build of any foss variant if one of them reaches its runtime classpath.
val verifyFossHasNoGms by tasks.registering {
    val classpaths = listOf("fossDebugRuntimeClasspath", "fossReleaseRuntimeClasspath").map { configurations.named(it) }
    doLast {
        val bad = classpaths.flatMap { cfg ->
            cfg.get().incoming.resolutionResult.allComponents.map { it.moduleVersion }.filterNotNull()
                .filter { it.group.startsWith("com.google.android.gms") || it.group.startsWith("com.google.firebase") }
                .map { "${it.group}:${it.name}" }
        }.distinct()
        check(bad.isEmpty()) { "The foss flavor has proprietary dependencies: $bad" }
    }
}
tasks.matching { it.name == "preFossDebugBuild" || it.name == "preFossReleaseBuild" }.configureEach { dependsOn(verifyFossHasNoGms) }
