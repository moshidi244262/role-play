plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.moyer.roleplay"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.moyer.roleplay.next"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.2-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Delivered builds reuse the private upgrade key; CI's disposable debug key is only for tests.
    System.getenv("ROLEPLAY_SIGNING_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
        val delivery = signingConfigs.create("delivery") {
            storeFile = file(path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        buildTypes.getByName("debug").signingConfig = delivery
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.re2j:re2j:1.8")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// A small JVM runtime lets private local fixtures be checked without uploading their contents.
tasks.register("exportCommunityProbeRuntime") {
    dependsOn("testDebugUnitTest")
    doLast {
        val target = layout.buildDirectory.dir("community-probe").get().asFile
        target.deleteRecursively()
        val tests = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get()
        tests.classpath.files.filter { it.exists() }.filter { it.isDirectory || it.path.startsWith(layout.buildDirectory.get().asFile.path) || it.name.contains("kotlin-stdlib") ||
            it.name.contains("kotlinx-serialization") || it.name.contains("re2j") ||
            it.name.contains("jsoup") || it.name.contains("annotations") }.forEachIndexed { index, source ->
            if(source.isDirectory) source.copyRecursively(target.resolve("classes-$index"),overwrite=true)
            else source.copyTo(target.resolve("$index-${source.name}"),overwrite=true)
        }
    }
}
