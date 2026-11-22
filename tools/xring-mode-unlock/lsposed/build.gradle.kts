plugins { id("com.android.application") }

android {
    namespace = "com.extbridge.refreshunlock"
    compileSdk = 37
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "com.extbridge.refreshunlock"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.directories.apply { clear(); addAll(listOf("src", "shared")) }
        res.directories.apply { clear(); add("res") }
        assets.directories.apply { clear(); add("assets") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(".extbridge-debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}

dependencies { compileOnly(files("../../../lsposed/libs/api-82.jar")) }
