import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.serogee.hive.server.MainKt")
}

dependencies {
    implementation(project(":shared:host"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    implementation(libs.sqlite.jdbc)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.embedded.postgres)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("resetDemo") {
    group = "application"
    description = "Replaces the demo database with fresh seed data."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args("--reset-demo")
}
