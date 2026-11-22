import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val customKeystore = providers.environmentVariable("EXTBRIDGE_KEYSTORE").orNull
    ?.let { rootProject.file(it) }?.takeIf { it.isFile }
val legacyKeystore = rootProject.file("exthelper.p12").takeIf { it.isFile }
val signingKeystore = customKeystore ?: legacyKeystore ?: rootProject.file(".extbridge-debug.keystore")
val signingPassword = if (customKeystore != null) {
    providers.environmentVariable("EXTBRIDGE_KEYSTORE_PASS").getOrElse("extbridge")
} else if (legacyKeystore != null) "extbridge" else "android"
val signingAlias = if (customKeystore != null || legacyKeystore != null) {
    val keys = KeyStore.getInstance(signingKeystore, signingPassword.toCharArray())
    keys.aliases().toList().first { keys.isKeyEntry(it) }
} else "androiddebugkey"

android {
    namespace = "com.extbridge.helper"
    compileSdk = 37
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "com.extbridge.helper"
        minSdk = 33
        targetSdk = 36
        versionCode = 28
        versionName = "0.0.1"
        testInstrumentationRunner = "com.extbridge.tests.WindowRegression"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.directories.apply { clear(); addAll(listOf("src", "../bridge/src", "../tools/xring-mode-unlock/lsposed/src",
            "../tools/xring-mode-unlock/lsposed/shared")) }
        kotlin.directories.apply { clear(); add("src") }
        res.directories.apply { clear(); add("res") }
        assets.directories.apply { clear(); addAll(listOf("assets", "build/generated/daemonAssets",
            "build/generated/rootAssets")) }
        jniLibs.directories.apply { clear(); add("jniLibs") }
    }
    sourceSets["androidTest"].java.setSrcDirs(listOf("tests"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { jniLibs.useLegacyPackaging = true }
    signingConfigs.getByName("debug") {
        storeFile = signingKeystore
        storePassword = signingPassword
        keyAlias = signingAlias
        keyPassword = signingPassword
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    compileOnly(files("../lsposed/libs/api-82.jar"))
}

// The shell/root bootstrap consumes a standalone dex jar as an APK asset.
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val compileDaemon = tasks.register<JavaCompile>("compileDaemon") {
    source = fileTree("../bridge/src") { include("**/*.java") }
    classpath = files(androidComponents.sdkComponents.bootClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("daemon/classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.encoding = "UTF-8"
}
val daemonJar = tasks.register<Jar>("daemonJar") {
    dependsOn(compileDaemon)
    from(compileDaemon.flatMap { it.destinationDirectory })
    archiveFileName.set("daemon-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("daemon"))
}
val dexDaemon = tasks.register<JavaExec>("dexDaemon") {
    dependsOn(daemonJar)
    val outputDir = layout.buildDirectory.dir("generated/daemonAssets")
    inputs.file(daemonJar.flatMap { it.archiveFile })
    outputs.file(outputDir.map { it.file("extbridge.jar") })
    classpath = files(sdkDirectory.map { it.file("build-tools/36.1.0/lib/d8.jar") })
    mainClass.set("com.android.tools.r8.D8")
    doFirst {
        outputDir.get().asFile.mkdirs()
        args = listOf("--min-api", "30", "--lib",
            androidComponents.sdkComponents.bootClasspath.get().first().asFile.path,
            "--output", outputDir.get().file("extbridge.jar").asFile.path,
            daemonJar.get().archiveFile.get().asFile.path)
    }
}
tasks.named("preBuild") { dependsOn(dexDaemon) }

val bundleRootTools = tasks.register<Sync>("bundleRootTools") {
    into(layout.buildDirectory.dir("generated/rootAssets/root-tools"))
    from("assets-root-tools") { include("install.sh") }
    from("../tools/xring-mode-unlock") { include("external-refresh.sh") }
    from("../tools/xring-mode-unlock/prebuilt/violin") {
        include("drm-read-current", "live-mode-table", "extbridge_618_mode_cap.ko")
    }
    into("manual-v1") {
        from("../tools/xring-mode-unlock") { include("manual-refresh.sh", "trace-dp-policy.sh") }
        from("../tools/xring-mode-unlock/prebuilt/violin") {
            include("manual-timing", "manual-selector", "drm-read-props")
        }
    }
}
tasks.named("preBuild") { dependsOn(bundleRootTools) }

tasks.register<Zip>("bundleAutostartModule") {
    dependsOn(dexDaemon)
    from("assets/autostart")
    from(layout.buildDirectory.file("generated/daemonAssets/extbridge.jar"))
    archiveFileName.set("ExtBridge-Autostart-4.7.0.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/module"))
}

tasks.register<Copy>("exportDebug") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/helper-debug.apk")) { rename { "exthelper.apk" } }
    from(layout.buildDirectory.file("generated/daemonAssets/extbridge.jar"))
    into(rootProject.layout.buildDirectory.dir("out"))
}
