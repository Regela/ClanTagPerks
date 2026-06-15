plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "me.regela"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") // velocity-api
    mavenCentral()                                            // luckperms api, mariadb driver
}

dependencies {
    // Velocity API provides gson + snakeyaml transitively at compile time,
    // and supplies them at runtime — so we DO NOT shade them.
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")

    compileOnly("net.luckperms:api:5.5")

    // Configurate (YAML config binding) is provided by Velocity at runtime — compileOnly, not shaded.
    compileOnly("org.spongepowered:configurate-yaml:4.2.0")

    // Only dependency we bundle: JDBC driver for limboauth mode (Velocity does not provide it).
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    // Tests: velocity-api brings gson/snakeyaml/configurate transitively; pin the ones we use directly.
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.spongepowered:configurate-yaml:4.2.0")
    testImplementation("net.luckperms:api:5.5")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        // Relocate the driver so it never clashes with LuckPerms' own bundled driver.
        relocate("org.mariadb.jdbc", "me.regela.clantagperks.libs.mariadb")
        // Trim noise the driver pulls in but we don't use.
        minimize {
            exclude(dependency("org.mariadb.jdbc:.*:.*"))
        }
    }
    build {
        dependsOn(shadowJar)
    }
    compileJava {
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform()
    }
}
