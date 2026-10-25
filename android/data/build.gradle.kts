plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.gymtracker.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("com.gymtracker:shared:0.1.0")
    testImplementation(kotlin("test"))
}
