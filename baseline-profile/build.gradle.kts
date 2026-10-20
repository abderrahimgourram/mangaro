import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(mihonx.plugins.android.test)
    alias(libs.plugins.androidx.baselineProfile)
}

android {
    namespace = "mihon.baselineprofile"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    // This code creates the gradle managed device used to generate baseline profiles.
    // To use GMD please invoke generation through the command line:
    // ./gradlew :app:generateBaselineProfile
    testOptions.managedDevices.allDevices {
        @Suppress("UnstableApiUsage")
        create<ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "google"
        }
    }
}

// This is the configuration block for the Baseline Profile plugin.
// You can specify to run the generators on a managed devices or connected devices.
baselineProfile {

    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macroJunit4)
    constraints {
        implementation("com.squareup.wire:wire-runtime:6.4.5") {
            because("GHSA-7xpr-hc2w-34m9 and GHSA-9rm7-3qhh-h2mc: validate protobuf lengths")
        }
    }
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
}

androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        val applicationId = variant.testedApks.map { artifactsLoader.load(it)?.applicationId }
        variant.instrumentationRunnerArguments.put("targetAppId", applicationId)
    }
}
