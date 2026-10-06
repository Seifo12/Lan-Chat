plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.lanchat.offline.messenger"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.lanchat.offline.messenger"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if (keystoreFile.exists()) {
        storeFile = keystoreFile
        storePassword = System.getenv("STORE_PASSWORD") ?: "android"
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      val releaseKeystore = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
      signingConfig = if (releaseKeystore.exists()) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debug")
      }
    }
  }
  
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  
  buildFeatures {
    compose = true
    buildConfig = true
  }
  
  testOptions { 
    unitTests { isIncludeAndroidResources = true } 
  }
  
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

/**
 * Stages the exported Room schemas so a Robolectric migration test can read them
 * from assets. app/schemas stays the single source of truth, so nothing is
 * duplicated in git.
 *
 * MigrationTestHelper builds the old database out of the exported schema JSON and
 * reads it from assets. AGP 9 does not merge unit-test source set assets into the
 * APK a Robolectric test runs against, so the schemas are mounted on the debug
 * variant, which is the variant the unit test APK is built from. Release is
 * untouched.
 */
abstract class SyncRoomSchemaAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemasDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        schemasDir.get().asFile.copyRecursively(target, overwrite = true)
    }
}

val syncRoomSchemas by tasks.registering(SyncRoomSchemaAssets::class) {
    schemasDir.set(layout.projectDirectory.dir("schemas"))
}

androidComponents {
    onVariants(selector().withName("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncRoomSchemas,
            SyncRoomSchemaAssets::outputDir,
        )
    }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation("com.google.android.gms:play-services-nearby:19.3.0")
  implementation(libs.zxing.core)
  implementation(libs.zxing.android.embedded)
  
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

ksp {
    // Schemas are exported so Room migrations can be regression-tested with
    // MigrationTestHelper against real historical definitions, instead of
    // hand-written DDL that can silently drift from what Room expects.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}