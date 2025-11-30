plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "dev.anton"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.anton.MainKt")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes["Main-Class"] = "dev.anton.MainKt"
    }
}
