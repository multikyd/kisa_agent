import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.security.KeyStore
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigning = Properties().apply {
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}
val hasReleaseSigning = !releaseSigning.getProperty("storePassword", "").isBlank()
        && !releaseSigning.getProperty("keyPassword", "").isBlank()


android {
    namespace = "com.kisapilot.kisaagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kisapilot.kisaagent"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
        targetSdk = 36
        versionCode = 47
        versionName = "1.0.37"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("kisaRelease") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("kisaRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        if (buildType.name == "release" || buildType.name == "debug") {
            outputs.forEach { output ->
                val newApkName = "kisa_agent.apk"
                (output as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = newApkName
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.google.mediapipe:tasks-vision:1.1.1")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.mediation.test.suite)
    implementation(libs.firebase.inappmessaging)
    implementation(libs.activity)

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.register("requireReleaseSigning") {
    doLast {
        check(hasReleaseSigning) { "Fill storePassword and keyPassword in local signing.properties before publishing." }
    }
}

// Build and publish the signed APK and matching metadata as one operation.
tasks.register("publishKisaAgent") {
    dependsOn("requireReleaseSigning", "assembleRelease")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/kisa_agent.apk").get().asFile
        check(apk.isFile) { "Release APK was not generated." }
        val sdk = android.sdkDirectory
        val buildTools = sdk.resolve("build-tools/${android.buildToolsVersion}")
        val verify = ProcessBuilder("cmd", "/c", buildTools.resolve("apksigner.bat").absolutePath,
            "verify", "--print-certs", apk.absolutePath).redirectErrorStream(true).start()
        val verification = verify.inputStream.bufferedReader().use { it.readText() }
        check(verify.waitFor() == 0) { "APK signature verification failed: $verification" }
        ZipFile(apk).use { zip ->
            check(zip.getEntry("resources.arsc")?.method == ZipEntry.STORED) {
                "resources.arsc must be stored uncompressed for Android installation."
            }
        }
        val alignment = ProcessBuilder(buildTools.resolve("zipalign.exe").absolutePath,
            "-c", "-P", "16", "4", apk.absolutePath).redirectErrorStream(true).start()
        val alignmentOutput = alignment.inputStream.bufferedReader().use { it.readText() }
        check(alignment.waitFor() == 0) { "APK alignment check failed: $alignmentOutput" }
        val keyStore = KeyStore.getInstance("JKS")
        rootProject.file(releaseSigning.getProperty("storeFile")).inputStream().use {
            keyStore.load(it, releaseSigning.getProperty("storePassword").toCharArray())
        }
        val signerHash = MessageDigest.getInstance("SHA-256")
            .digest(keyStore.getCertificate(releaseSigning.getProperty("keyAlias")).encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(verification.contains(signerHash, ignoreCase = true)) { "Release APK signing key does not match." }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val destination = rootProject.file(providers.gradleProperty("apkPublishDir")
            .getOrElse(rootProject.projectDir.absolutePath))
        check(destination.isDirectory) { "APK publication directory does not exist: $destination" }
        val metadata = linkedMapOf<String, Any>(
            "versionCode" to android.defaultConfig.versionCode!!,
            "versionName" to android.defaultConfig.versionName!!,
            "apkUrl" to "https://raw.githubusercontent.com/multikyd/kisa_agent/main/kisa_agent.apk",
            "sha256" to sha256,
            "releaseNotes" to mapOf(
                "ko" to "- 실차 진단 로그를 반영해 표준 PID 해석과 배터리 제어 상태·유입 온도·팩 전력 표시를 추가했습니다. 셀 192개의 전압 요약과 보고 단위를 표시하고 전체·개별 진단 결과의 중복 저장을 수정했습니다.",
                "en" to "- Decode additional recorded PIDs and display battery control status, inlet temperature, and calculated power. Add cell voltage statistics and resolution, and prevent duplicate full/individual diagnostic results."
            )
        )
        val backup = layout.buildDirectory.dir("publication-backup").get().asFile.apply { mkdirs() }
        listOf("kisa_agent.apk", "kisa_agent_latest.json").forEach { name ->
            destination.resolve(name).takeIf { it.exists() }?.copyTo(backup.resolve(name), overwrite = true)
        }
        val stagedApk = destination.resolve("kisa_agent.apk.tmp")
        val stagedJson = destination.resolve("kisa_agent_latest.json.tmp")
        apk.copyTo(stagedApk, overwrite = true)
        stagedJson.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(metadata)) + "\n", Charsets.UTF_8)
        Files.move(stagedApk.toPath(), destination.resolve("kisa_agent.apk").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(stagedJson.toPath(), destination.resolve("kisa_agent_latest.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
        println("Published kisa_agent.apk and kisa_agent_latest.json to $destination")
    }
}
