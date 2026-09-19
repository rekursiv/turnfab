
plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.gradlex.extra-java-module-info") version "1.8"
}

val appName = project.name

repositories {
    mavenCentral()
    maven {
        url = uri("https://github.com/poolborges/maven/raw/master/thirdparty/")
    }
}

dependencies {
    implementation("javax.inject:javax.inject:1")
    implementation(libs.google.guice)
    implementation(libs.google.guava)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.core)
    implementation(libs.jackson.annotations)
    implementation(libs.fx.guice)
    implementation(libs.junique)

    implementation("dev.langchain4j:langchain4j:1.20.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.20.0")
    implementation("dev.langchain4j:langchain4j-http-client-jdk:1.20.0")
    implementation("dev.langchain4j:langchain4j-mcp:1.20.0-beta30")

    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

extraJavaModuleInfo {
    failOnMissingModuleInfo = false
    deriveAutomaticModuleNamesFromFileNames = true
    automaticModule("javax.inject:javax.inject", "java.inject")
    module("com.cathive.fx:fx-guice", "com.cathive.fx.guice") {
        exports("com.cathive.fx.guice")
        requires("com.google.guice")
        requires("java.inject")
        requires("aopalliance")
        requires("javafx.controls")
        requires("javafx.fxml")
    }
}

javafx {
    version = "25"
    modules("javafx.controls", "javafx.fxml", "javafx.web")
}

application {
    mainClass.set("dev.${project.name}.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=javafx.graphics")
}

val copyLibs = tasks.register<Copy>("copyLibs") {
    description = "Emulates Eclipse-style JAR export with required libraries in sub-folder"
    // Copy all dependencies INCLUDING JavaFX
    // from(configurations.runtimeClasspath)

    // Copy all dependencies EXCEPT JavaFX
    from(configurations.runtimeClasspath.get().filter { file ->
        !file.name.contains("javafx")
    })

    into(layout.buildDirectory.dir("libs/${appName}_lib"))
}

tasks.named<Jar>("jar") {
    archiveFileName.set("${appName}.jar")
    dependsOn(copyLibs) // Ensures lib folder is created every time you build

    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Class-Path" to provider {
                configurations.runtimeClasspath.get().files.joinToString(" ") { file ->
                    "${appName}_lib/${file.name}"
                }
            }
        )
    }
}