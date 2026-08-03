import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val repositoryRoot = rootProject.projectDir.parentFile
val gitMetadataPresent = repositoryRoot.resolve(".git").exists()
val gitRevision = providers.exec {
    workingDir(repositoryRoot)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }
val sourceRevision = providers.environmentVariable("SOURCE_REVISION").orElse(gitRevision)
val declaredSourceDirty = providers.environmentVariable("SOURCE_DIRTY").map { raw ->
    require(raw == "true" || raw == "false") { "SOURCE_DIRTY must be true or false" }
    raw.toBooleanStrict()
}
val sourceDirty = if (gitMetadataPresent) {
    providers.exec {
        workingDir(repositoryRoot)
        commandLine("git", "status", "--porcelain=v1", "--untracked-files=normal")
    }.standardOutput.asText.map { it.isNotBlank() }
} else {
    declaredSourceDirty.orElse(true)
}
val requireCleanSource = providers.gradleProperty("requireCleanSource")
    .orElse(providers.environmentVariable("REQUIRE_CLEAN_SOURCE"))
    .map { raw ->
        require(raw == "true" || raw == "false") {
            "requireCleanSource/REQUIRE_CLEAN_SOURCE must be true or false"
        }
        raw.toBooleanStrict()
    }
    .orElse(false)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.serialization)
}

android {
    namespace = "app.momoding"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.momoding"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val resolvedSourceRevision = sourceRevision.get()
        require(Regex("^[0-9a-f]{40}$").matches(resolvedSourceRevision)) {
            "SOURCE_REVISION must be an exact 40-character Git commit"
        }
        val resolvedSourceDirty = sourceDirty.get()
        require(!requireCleanSource.get() || !resolvedSourceDirty) {
            "Clean-source build requested, but the Git worktree has tracked or untracked changes"
        }
        require(
            !requireCleanSource.get() ||
                !gitMetadataPresent ||
                resolvedSourceRevision == gitRevision.get()
        ) {
            "Clean-source build requested, but SOURCE_REVISION does not match Git HEAD"
        }
        buildConfigField("String", "SOURCE_REVISION", "\"$resolvedSourceRevision\"")
        buildConfigField("boolean", "SOURCE_DIRTY", resolvedSourceDirty.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    androidResources {
        // AAPT otherwise inflates *.gz assets and silently strips the suffix.
        noCompress += "gz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }

    packaging {
        jniLibs {
            // The app executes the APK-embedded PRoot binary from nativeLibraryDir. Android 10+
            // forbids executing a copy from the writable app data directory.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.getByName("debug").assets.srcDir(
        layout.buildDirectory.get().dir("generated/p2-fixtures/debug").asFile,
    )
    sourceSets.getByName("debug").assets.srcDir(
        layout.buildDirectory.get().dir("generated/phone-local-linux-runtime/assets").asFile,
    )
    sourceSets.getByName("debug").jniLibs.srcDir(
        layout.buildDirectory.get().dir("generated/phone-local-linux-runtime/jniLibs").asFile,
    )
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.wire.contract)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.quickjs.kt)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.androidx.exifinterface)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    ksp(libs.androidx.room.compiler)
}

val p2FixtureSource = rootProject.layout.projectDirectory.file("../docs/design/fixtures/r0-contract-fixtures.json")
val p2FixtureGenerator = rootProject.layout.projectDirectory.file("../scripts/generate-p2-fixtures.mjs")
val p2FixtureLibrary = rootProject.layout.projectDirectory.file("../scripts/lib/p2-fixture-projection.mjs")
val p2FixtureOutput = layout.buildDirectory.file("generated/p2-fixtures/debug/p2-fixtures.json")
val phoneLocalLinuxRuntimeBuilder =
    rootProject.layout.projectDirectory.file("../scripts/build-phone-local-linux-runtime.sh")
val phoneLocalProotPatch =
    rootProject.layout.projectDirectory.file("../third_party/patches/proot-5.1.107.86-android-ndk.patch")
val phoneLocalLinuxRuntimeRoot = layout.buildDirectory.dir("generated/phone-local-linux-runtime")

val generateP2FixtureProjection by tasks.registering(Exec::class) {
    inputs.files(p2FixtureSource, p2FixtureGenerator, p2FixtureLibrary)
    outputs.file(p2FixtureOutput)
    commandLine(
        "node",
        p2FixtureGenerator.asFile.absolutePath,
        p2FixtureSource.asFile.absolutePath,
        p2FixtureOutput.get().asFile.absolutePath,
    )
}

val preparePhoneLocalLinuxRuntime by tasks.registering(Exec::class) {
    inputs.files(phoneLocalLinuxRuntimeBuilder, phoneLocalProotPatch)
    outputs.files(
        phoneLocalLinuxRuntimeRoot.map { it.file("jniLibs/arm64-v8a/libmomoding_proot.so") },
        phoneLocalLinuxRuntimeRoot.map { it.file("jniLibs/arm64-v8a/libmomoding_proot_loader.so") },
        phoneLocalLinuxRuntimeRoot.map {
            it.file("assets/phone-local-runtime/alpine-minirootfs-3.23.5-aarch64.tgz")
        },
        phoneLocalLinuxRuntimeRoot.map { it.file("runtime-manifest.json") },
    )
    commandLine(phoneLocalLinuxRuntimeBuilder.asFile.absolutePath)
}

tasks.matching {
    it.name == "mergeDebugAssets" ||
        (it.name.contains("Debug") && it.name.contains("lint", ignoreCase = true))
}.configureEach {
    dependsOn(generateP2FixtureProjection)
}

tasks.matching {
    it.name == "mergeDebugAssets" ||
        it.name == "mergeDebugJniLibFolders" ||
        it.name == "mergeDebugNativeLibs" ||
        (it.name.contains("Debug") && it.name.contains("lint", ignoreCase = true))
}.configureEach {
    dependsOn(preparePhoneLocalLinuxRuntime)
}
