plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Android 앱에서도 쓰이므로 Java 17 바이트코드로 맞춘다.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.smbj)
    implementation(libs.bouncycastle)
    implementation(libs.junrar)
    implementation(libs.commons.compress)
    implementation(libs.commons.net)
    implementation(libs.xz)
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
