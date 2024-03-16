// values should match version in buildSrc/../Build.kt
group = "org.dbtools"
version = "1.4.0"

// ./gradlew publishPlugins
gradlePlugin {
    website = "https://github.com/jeffdcamp/license-manager-plugin"
    vcsUrl = "https://github.com/jeffdcamp/license-manager-plugin.git"
    plugins {
        create("licenseManager") {
            id = "org.dbtools.license-manager"
            displayName = "Plugin for building a list of dependency licenses"
            description = "License Manager is a library that makes generating a json file of all of a project dependencies easy."
            tags = listOf("dependency", "licenses")
            implementationClass = "org.dbtools.licensemanager.LicenseManagerPlugin"
        }
    }
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(kotlin("gradle-plugin", embeddedKotlinVersion))
        classpath("org.jetbrains.kotlin:kotlin-serialization:$embeddedKotlinVersion")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    withJavadocJar()
}

// Kotlin Libraries targeting Java8 bytecode can cause the following error (such as okHttp 4.x):
// "Cannot inline bytecode built with JVM target 1.8+ into bytecode that is being built with JVM target 1.6. Please specify proper '-jvm-target' option"
// The following is added to allow the Kotlin Compiler to compile properly
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

plugins {
    alias(libs.plugins.versions) // ./gradlew dependencyUpdates -Drevision=release
    alias(libs.plugins.gradle.publish) // ./gradlew dependencyUpdates -Drevision=release
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
//    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$embeddedKotlinVersion")
    implementation(libs.kotlin.serialization.json)

    // xml parsing
    implementation(libs.jackson.xml)

    // network
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    // Test
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.engine)
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
        maven {
            name = "localPluginRepository"
            url = uri("../local-plugin-repository")
        }
    }
}

signing {
    sign(publishing.publications["maven"])
}
