plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "dev.aquestry"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-html-builder:2.3.7")
    implementation("io.ktor:ktor-server-sessions:2.3.7")
    implementation("com.charleskorn.kaml:kaml:0.55.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.2")
    implementation("com.github.docker-java:docker-java-core:3.6.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.6.0")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.aquestry.gaterouter.MainKt")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes["Main-Class"] = "dev.aquestry.gaterouter.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}