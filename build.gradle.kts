
group = "org.jdc"
version = "1.0-SNAPSHOT"

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(kotlin("gradle-plugin", embeddedKotlinVersion))
        classpath("org.jetbrains.kotlin:kotlin-serialization:$embeddedKotlinVersion")
        classpath("com.github.ben-manes:gradle-versions-plugin:0.39.0") // version plugin support
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8

    withJavadocJar()
}

// Kotlin Libraries targeting Java8 bytecode can cause the following error (such as okHttp 4.x):
// "Cannot inline bytecode built with JVM target 1.8 into bytecode that is being built with JVM target 1.6. Please specify proper '-jvm-target' option"
// The following is added to allow the Kotlin Compiler to compile properly
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

plugins {
    id("com.github.ben-manes.versions") version "0.39.0"  // ./gradlew dependencyUpdates -Drevision=release
    kotlin("plugin.serialization") version embeddedKotlinVersion
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
    signing
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$embeddedKotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.2.2")

    // xml parsing
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.12.3")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
}

gradlePlugin {
    plugins {
        create("licenseManager") {
            id = "org.dbtools.license-manager"
            implementationClass = "org.dbtools.licensemanager.LicenseManagerPlugin"
        }
    }
}

// ./gradlew clean jar publishMavenPublicationToMavenLocal
// ./gradlew clean jar publishMavenPublicationToMavenCentralRepository
tasks.register<Jar>("sourcesJar") {
    from(sourceSets.main.get().allJava)
    archiveClassifier.set("sources")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = Pom.GROUP_ID
            artifactId = Pom.LIBRARY_ARTIFACT_ID
            version = Pom.VERSION_NAME
            from(components["java"])
            artifact(tasks["sourcesJar"])
            pom {
                name.set(Pom.LIBRARY_NAME)
                description.set(Pom.POM_DESCRIPTION)
                url.set(Pom.URL)
                licenses {
                    license {
                        name.set(Pom.LICENCE_NAME)
                        url.set(Pom.LICENCE_URL)
                        distribution.set(Pom.LICENCE_DIST)
                    }
                }
                developers {
                    developer {
                        id.set(Pom.DEVELOPER_ID)
                        name.set(Pom.DEVELOPER_NAME)
                    }
                }
                scm {
                    url.set(Pom.SCM_URL)
                    connection.set(Pom.SCM_CONNECTION)
                    developerConnection.set(Pom.SCM_DEV_CONNECTION)
                }
            }
        }
    }
    repositories {
        maven {
            name = "MavenCentral"
            url = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
            credentials {
                val sonatypeNexusUsername: String? by project
                val sonatypeNexusPassword: String? by project
                username = sonatypeNexusUsername ?: ""
                password = sonatypeNexusPassword ?: ""
            }
        }
    }
}

signing {
    sign(publishing.publications["maven"])
}
