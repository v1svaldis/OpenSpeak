import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------- Rust core (core/)
// Protocol, voice and crypto live in libopenspeak_core.so, built by cargo for each ABI.

val rustAbis: List<String> =
    (findProperty("rustAbis") as String? ?: "arm64-v8a,x86_64").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val rustTargets = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")
val rustDir = rootProject.file("core")
val rustJniDir = layout.buildDirectory.dir("rustJniLibs").get().asFile

val sdkDir: String = System.getenv("ANDROID_HOME") ?: Properties().run {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    getProperty("sdk.dir") ?: ""
}

val cargoTasks = rustAbis.map { abi ->
    val target = rustTargets.getValue(abi)
    tasks.register<Exec>("cargoBuild-$abi") {
        group = "rust"
        description = "Builds the Rust core for $abi"
        workingDir = rustDir
        commandLine("cargo", "build", "--release", "--target", target)
        // libopus is built with CMake + Ninja from the SDK.
        val cmakeBin = File(sdkDir, "cmake/3.22.1/bin").absolutePath
        environment("PATH", cmakeBin + File.pathSeparator + System.getenv("PATH"))
        if (System.getenv("ANDROID_NDK_HOME") == null) environment("ANDROID_NDK_HOME", File(sdkDir, "ndk/27.2.12479018").absolutePath)
        inputs.dir(File(rustDir, "src"))
        inputs.file(File(rustDir, "Cargo.toml"))
        outputs.file(File(rustDir, "target/$target/release/libopenspeak_core.so"))
        doLast {
            copy {
                from(File(rustDir, "target/$target/release/libopenspeak_core.so"))
                into(File(rustJniDir, abi))
            }
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(cargoTasks)
}

android {
    namespace = "com.v1svaldis.openspeak"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.v1svaldis.openspeak"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        ndk {
            // Real phones are arm64; x86_64 is for the emulator. -PrustAbis=arm64-v8a builds one.
            abiFilters += rustAbis
        }
    }

    sourceSets["main"].jniLibs.srcDir(rustJniDir)

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
    }
    packaging {
        // Compressed native libraries: the APK shrinks by more than half (the .so files compress ~3x).
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
