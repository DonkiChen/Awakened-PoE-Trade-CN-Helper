import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm") version "1.9.23"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateTranslations") {
    group = "application"
    description = "Builds and runs the Chinese translation data generator."
    dependsOn(tasks.named("build"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("Main")
}

kotlin {
    jvmToolchain(8)
    compilerOptions {
//        freeCompilerArgs.add("-Xdebug")
    }
}
