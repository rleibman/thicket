plugins { id("com.android.application") }

android {
  namespace  = "example.gallery.android"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.thicket.gallery"
    minSdk        = 26
    targetSdk     = 36
    versionCode   = 1
    versionName   = "1.0"
  }

  // Scala 3.9 cannot emit bytecode below Java 17 (S2).
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // MANDATORY for Scala (S2). scala3-library ships ~7.5 MB of `.tasty` as jar *resources*.
  // R8 shrinks code; isShrinkResources only touches Android res/. Nothing else removes
  // them, so without this the APK is 3.89 MB instead of ~200 KB. TASTy is compile-time only.
  packaging {
    resources {
      excludes += setOf("**/*.tasty", "rootdoc.txt", "library.properties", "META-INF/*.txt")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled   = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "scala3.pro")
      signingConfig = signingConfigs.getByName("debug") // demo: installable and measurable
    }
    debug {
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}

dependencies {
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
  implementation("org.scala-lang:scala3-library_3:3.9.0")
  // The gallery runs one ZIO effect (its Effects section), so the bridge's runtime ships with it.
  implementation("dev.zio:zio_3:2.1.26")
  implementation("dev.zio:zio-streams_3:2.1.26")
  // The HTTP checks: sttp and caliban-client, at build.sbt's sttpV and calibanV.
  implementation("com.softwaremill.sttp.client4:core_3:4.1.0")
  implementation("com.softwaremill.sttp.client4:jsoniter_3:4.1.0")
  implementation("com.softwaremill.sttp.client4:json-common_3:4.1.0")
  implementation("com.github.ghostdogpr:caliban-client_3:3.2.0")
}
