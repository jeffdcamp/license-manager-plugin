License Manager for Gradle projects
===================================

License Manager is a library that makes generating a json file of all of a project dependencies easy.  

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.dbtools/license-manager-gradle-plugin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.dbtools/license-manager-gradle-plugin)

Install
=======
Android Example in build.gradle.kts

```kotlin
buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath("org.dbtools:license-manager-gradle-plugin:<latest-version-here>")
    }
}

plugins {
    id("org.dbtools.license-manager")
}

licenseManager {
    createHtmlReport = true // default true
    createJsonReport = true // default false
    createCsvReport = true // default false

    // Needed for Android projects only
    variantName = "release"
    
    // optional - default: ./build/licenses
    outputDirs = listOf(
        "./src/main/assets",
        "./build/licenses"
    )
    
    // optional - List of groupIds to be excluded
    excludeGroups = listOf(
        "com.commercial.groupId"
    )
}
```

Usage
=====

    ./gradlew createLicenseReports

License
=======

    Copyright 2021 Jeff Campbell

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
