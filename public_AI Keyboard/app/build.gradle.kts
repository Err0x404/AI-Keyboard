import com.android.build.api.variant.HasUnitTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val rimeSourceDir = providers.gradleProperty("rimeSourceDir").orNull.orEmpty()

android {
    namespace = "com.coinrangersdebthero.aikeyboard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.coinrangersdebthero.aikeyboard"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0-sprint0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DRIME_SOURCE_DIR=$rimeSourceDir")
            }
        }
    }

    sourceSets {
        getByName("debug") {
            assets.directories.add(rootProject.file("models").absolutePath)
        }
    }

    assetPacks += listOf(":modelpack")

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    buildFeatures {
        compose = true
    }
    
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// 透過 androidComponents 區塊強制所有 variant 啟用單元測試，滿足 VS Code Compose 預覽需求
androidComponents {
    beforeVariants { variant ->
        (variant as HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.llamacpp.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}