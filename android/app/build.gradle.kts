import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "com.scrobblecue.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.scrobblecue.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // The phone reaches the backend on the PC through `adb reverse tcp:8080 tcp:8080`
            buildConfigField("String", "API_BASE_URL", "\"http://localhost:8080/\"")
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.scrobblecue.app/\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add("build/generated/openapi/src/main/kotlin")
        }
        // The exported database schemas, for migration tests. Robolectric reads assets from the app
        // variant, not the test source set, so they go into debug builds (a few KB; never in release).
        getByName("debug") {
            assets.directories.add("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// The API client is generated from the contract the backend publishes (api/openapi.json), so the app
// fails to compile when it no longer matches the backend.
val generateApiClient = tasks.named<GenerateTask>("openApiGenerate") {
    generatorName = "kotlin"
    // As a URI: the generator rejects plain Windows paths
    inputSpec = rootProject.file("../api/openapi.json").toURI().toString()
    outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.path
    packageName = "com.scrobblecue.app.api"
    library.set("jvm-retrofit2")
    configOptions = mapOf(
        "serializationLibrary" to "kotlinx_serialization",
        "useCoroutines" to "true",
        "dateLibrary" to "string",
        "enumPropertyNaming" to "UPPERCASE",
        "omitGradleWrapper" to "true",
    )
    // Only the Retrofit interfaces and models; the HTTP client itself is set up in data/api/
    globalProperties = mapOf(
        "apis" to "",
        "models" to "",
        "supportingFiles" to "CollectionFormats.kt",
        "apiTests" to "false",
        "modelTests" to "false",
        "apiDocs" to "false",
        "modelDocs" to "false",
    )
    cleanupOutput = true
}

tasks.named("preBuild") {
    dependsOn(generateApiClient)
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
}
