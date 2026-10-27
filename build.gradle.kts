buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
        constraints {
            add("classpath", "org.apache.commons:commons-lang3:3.18.0") {
                because("GHSA-j288-q9x7-2f5v: bound recursive class-name processing")
            }
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6") {
                because("GHSA-3677-xxcr-wjqv: bound compressed JWE content")
            }
            add("classpath", "org.jdom:jdom2:2.0.6.1") {
                because("GHSA-2363-cqg2-863c: reject external XML entities")
            }
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.85") {
                because("Fix reviewed ASN.1, name-constraint, LDAP and GOST advisories through 1.85")
            }
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.85") {
                because("Keep Bouncy Castle build-time modules aligned; GHSA-wg6q-6289-32hp")
            }
            add("classpath", "org.bouncycastle:bcutil-jdk18on:1.85") {
                because("Keep Bouncy Castle build-time modules aligned")
            }
            add("classpath", "com.google.guava:guava:33.7.2-jre") {
                because("GHSA-xxph-c9ww-hj94 / CVE-2026-102554: bound deserialization allocation")
            }
        }
    }
}

plugins {
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineProfile) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.moko.resources) apply false
    alias(libs.plugins.sqldelight) apply false

    alias(mihonx.plugins.spotless)
}

val buildLogic: IncludedBuild = gradle.includedBuild("build-logic")
tasks {
    listOf("clean", "spotlessApply", "spotlessCheck").forEach { task ->
        named(task) {
            dependsOn(buildLogic.task(":$task"))
        }
    }
}
