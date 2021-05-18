package org.dbtools.licensemanager

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Pom(
    val name: String? = null,
    val groupId: String? = null,
    val artifactId: String? = null,
    val version: String? = null,
    val packaging: String? = null,
    val description: String? = null,
    val url: String? = null,
    val licenses: List<PomLicense>? = null,
    val organization: PomOrganization? = null,
    val developers: List<PomDevelopers>? = null,
//    val dependencies: List<PomDependencies>? = null,
    val scm: PomScm? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PomLicense(
    val name: String? = null,
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PomDevelopers(
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PomDependencies(
    val groupId: String? = null,
    val artifactId: String? = null,
    val version: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PomOrganization(
    val name: String? = null,
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PomScm(
    val url: String? = null
)