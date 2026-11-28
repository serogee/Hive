plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

/**
 * Server used until the user enters another address in Accounts. Set with -Phive.serverUrl=... Without it,
 * a debug app looks for a demo server on the computer of the emulator, and a release app has no server.
 */
val serverUrl = providers.gradleProperty("hive.serverUrl").orNull?.trim()

/** Turn Google sign-in on when all three are set. See the README. */
val supabaseUrl = providers.gradleProperty("hive.supabaseUrl").getOrElse("").trim()
val supabaseKey = providers.gradleProperty("hive.supabaseKey").getOrElse("").trim()
val googleWebClientId = providers.gradleProperty("hive.googleWebClientId").getOrElse("").trim()

/** The GitHub repository, as owner/name, whose releases Check for updates reads. */
val updatesRepo = providers.gradleProperty("hive.updatesRepo").getOrElse("serogee/Hive").trim()

/** Set by run app release, which takes the version from its argument. */
val appVersionName = providers.gradleProperty("hive.versionName").getOrElse("0.1.0")
val appVersionCode = providers.gradleProperty("hive.versionCode").map(String::toInt).getOrElse(1)

/** The release key, from the user's own gradle.properties. Without it, the release app is not signed. */
val signingStoreFile = providers.gradleProperty("hive.signing.storeFile").orNull

android {
    namespace = "io.github.serogee.hive"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.serogee.hive"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "UPDATES_REPO", "\"$updatesRepo\"")
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = providers.gradleProperty("hive.signing.storePassword").get()
                keyAlias = providers.gradleProperty("hive.signing.keyAlias").get()
                keyPassword = providers.gradleProperty("hive.signing.keyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"${serverUrl ?: "http://10.0.2.2:8080"}\"")
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"${serverUrl.orEmpty()}\"")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":shared:host"))
    implementation(libs.ktor.server.cio)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
