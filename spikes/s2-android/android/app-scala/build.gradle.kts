plugins { id("com.android.application") }

android {
  namespace  = "scalaui.s2"
  compileSdk = 36

  defaultConfig {
    applicationId = "scalaui.s2"
    minSdk        = 26
    targetSdk     = 36
    versionCode   = 1
    versionName   = "1.0"
  }

  // Scala 3.9 emits Java 17 bytecode and cannot emit less (see REPORT.md).
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  // S2 finding: scala3-library ships ~7.5 MB of `.tasty` metadata as jar RESOURCES.
  // R8 shrinks code, and `isShrinkResources` only touches Android res/, so without
  // this the APK carries all of it. TASTy is compile-time only; excluding it is safe
  // and takes the APK from 3.89 MB to ~0.2 MB. See REPORT.md.
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
      signingConfig = signingConfigs.getByName("debug") // spike only: measurable, installable
    }
  }
}

dependencies {
  implementation(files("../libs/s2-scala-lib.jar"))
  implementation("org.scala-lang:scala3-library_3:3.9.0")
}
