// AGP 9+ has built-in Kotlin support; the org.jetbrains.kotlin.android plugin is
// rejected outright (issuetracker 438678642).
plugins { id("com.android.application") }

android {
  namespace  = "scalaui.s2k"
  compileSdk = 36

  defaultConfig {
    applicationId = "scalaui.s2k"
    minSdk        = 26
    targetSdk     = 36
    versionCode   = 1
    versionName   = "1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildTypes {
    release {
      isMinifyEnabled   = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }
}
