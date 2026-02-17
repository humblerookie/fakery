plugins {
    kotlin("jvm")                  // version already resolved by root KMP plugin
    alias(libs.plugins.shadow)
    application
}

group   = "dev.anvith.fakery"
version = "0.1.0"

application {
    mainClass.set("dev.anvith.fakery.server.MainKt")
}

dependencies {
    implementation(project(":"))
}

// The shadow JAR is the primary artifact; disable the plain jar task to avoid confusion.
tasks.jar { enabled = false }
tasks.shadowJar {
    archiveBaseName.set("fakery-server")
    archiveClassifier.set("")
}
