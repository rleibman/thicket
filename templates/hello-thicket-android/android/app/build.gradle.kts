// This Gradle project is yours: change the id, the name, the SDK levels, add resources.
// `sbt thicketApk` (debug) or `sbt thicketApkRelease` fills libs/ with the Scala jars and runs
// Gradle; `./gradlew assembleDebug` here works too, once sbt has filled libs/.
plugins { id("com.android.application") }

android {
  namespace  = "hello"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.thicket.hello"
    minSdk        = 26
    targetSdk     = 36
    versionCode   = 1
    versionName   = "1.0"
  }

  // Scala 3.9 emits Java 17 bytecode at the lowest.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // Required for Scala: the Scala library ships ~7.5 MB of TASTy and metadata that nothing on
  // the device reads. Without this the APK is 3.89 MB instead of ~81 KB (S2).
  packaging {
    resources {
      excludes += setOf("**/*.tasty", "rootdoc.txt", "library.properties", "META-INF/*.txt")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled   = true
      isShrinkResources = true
      // scala3.pro keeps what Scala resolves by name at run time (required for Scala).
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "scala3.pro")
      // Signed with the debug key so a release build installs as it is. Replace with your own
      // signingConfig before you publish anything.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}

// Everything comes from sbt, the Scala library included: libs/ is the app's runtime
// classpath as sbt resolved it, so Gradle adds no second copy of anything.
dependencies {
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
