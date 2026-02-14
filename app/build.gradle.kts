import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
//    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))

    implementation(libs.okio)
    implementation(libs.bundles.log)
    implementation(libs.kotlinx.coroutines)

    ksp(libs.androidx.room.compiler)
    implementation(libs.bundles.androidx.room)

    testImplementation(kotlin("test"))
}

room {
    schemaDirectory("$projectDir/schemas")
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "moe.kurenai.app.AppKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
