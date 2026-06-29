import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

val devSigningStoreFile = providers.gradleProperty("devSigningStoreFile")
val devSigningStorePassword = providers.gradleProperty("devSigningStorePassword")
val devSigningKeyAlias = providers.gradleProperty("devSigningKeyAlias")
val devSigningKeyPassword = providers.gradleProperty("devSigningKeyPassword")

android {
    namespace = "com.tradingcards.uploader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tradingcards.uploader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val apiBaseUrl =
            providers.gradleProperty("apiBaseUrl").orElse("http://10.0.2.2:7071/api/")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get()}\"")
        val uploadApiScope =
            providers.gradleProperty("uploadApiScope").orElse(
                providers.gradleProperty("apiScope").orElse("api://replace-with-api-client-id/upload.write"),
            )
        buildConfigField("String", "UPLOAD_API_SCOPE", "\"${uploadApiScope.get()}\"")
        val galleryManageScope =
            providers
                .gradleProperty("galleryManageScope")
                .orElse("api://replace-with-api-client-id/gallery.manage")
        buildConfigField("String", "GALLERY_MANAGE_SCOPE", "\"${galleryManageScope.get()}\"")
        val msalRedirectPath =
            providers.gradleProperty("msalRedirectPath").orElse("PLACEHOLDER_SIGNATURE_HASH")
        manifestPlaceholders["msalRedirectPath"] = msalRedirectPath.get()
    }

    signingConfigs {
        if (devSigningStoreFile.isPresent) {
            create("devPhone") {
                storeFile = file(devSigningStoreFile.get())
                storePassword = devSigningStorePassword.get()
                keyAlias = devSigningKeyAlias.get()
                keyPassword = devSigningKeyPassword.get()
            }
        }
    }

    buildTypes {
        debug {
            if (devSigningStoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("devPhone")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val cameraVersion = "1.4.1"
    val roomVersion = "2.6.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.microsoft.identity.client:msal:5.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.register("validatePhoneBuildConfig") {
    group = "verification"
    description = "Fails phone APK builds when required dev runtime or signing values are missing."

    doLast {
        val requiredProperties =
            listOf(
                "apiBaseUrl",
                "uploadApiScope",
                "galleryManageScope",
                "msalRedirectPath",
                "msalClientId",
                "msalTenantId",
                "devSigningStoreFile",
                "devSigningStorePassword",
                "devSigningKeyAlias",
                "devSigningKeyPassword",
            )
        val values =
            requiredProperties.associateWith {
                providers.gradleProperty(it).orNull?.trim().orEmpty()
            }
        val missing = values.filterValues { it.isBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("Missing phone build properties: ${missing.joinToString(", ")}")
        }

        val placeholders =
            values
                .filterValues {
                    it.contains("replace-with", ignoreCase = true) ||
                        it.contains("placeholder", ignoreCase = true) ||
                        it == "00000000-0000-0000-0000-000000000000"
                }.keys
        if (placeholders.isNotEmpty()) {
            throw GradleException(
                "Phone build properties still contain placeholders: ${placeholders.joinToString(", ")}",
            )
        }

        val apiBaseUrl = values.getValue("apiBaseUrl")
        if (!apiBaseUrl.startsWith("https://")) {
            throw GradleException("apiBaseUrl must be an HTTPS dev Function URL for phone builds")
        }
        if (!apiBaseUrl.endsWith("/api/")) {
            throw GradleException("apiBaseUrl must end with /api/")
        }
        if (!values.getValue("uploadApiScope").endsWith("/upload.write")) {
            throw GradleException("uploadApiScope must end with /upload.write")
        }
        if (!values.getValue("galleryManageScope").endsWith("/gallery.manage")) {
            throw GradleException("galleryManageScope must end with /gallery.manage")
        }
        if (!file(values.getValue("devSigningStoreFile")).isFile) {
            throw GradleException("devSigningStoreFile does not exist")
        }
    }
}
