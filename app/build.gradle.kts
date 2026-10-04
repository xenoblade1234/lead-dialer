plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Each CI run gets a higher versionCode so Android accepts the APK as an update.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.leaddialer"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.leaddialer"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fixed key (decoded from GitHub secrets in CI) so updates install over the
    // old version without losing the lead database.
    signingConfigs {
        create("fixed") {
            val pw = System.getenv("KEYSTORE_PASSWORD") ?: ""
            storeFile = rootProject.file("keystore/dialer.jks")
            storePassword = pw
            keyAlias = "dialer"
            keyPassword = pw
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}
