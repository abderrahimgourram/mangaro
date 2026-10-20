import mihon.gradle.Config

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)
}

android {
    namespace = "mihon.telemetry"

    sourceSets {
        getByName("main") {
            if (Config.includeTelemetry) {
                kotlin.directories.add("src/firebase/kotlin")
            } else {
                kotlin.directories.add("src/noop/kotlin")
                manifest.srcFile("src/noop/AndroidManifext.xml")
            }
        }
    }
}

dependencies {
    implementation(projects.core.common)
    constraints {
        implementation("com.google.guava:guava:32.0.1-android") {
            because("GHSA-5mg8-w23w-74h3 and GHSA-7g45-4rm6-3mm3: safe temporary files")
        }
    }

    if (Config.includeTelemetry) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.analytics)
        implementation(libs.firebase.crashlytics)
    }
}
