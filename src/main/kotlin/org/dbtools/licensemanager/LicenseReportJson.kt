package org.dbtools.licensemanager

import kotlinx.serialization.Serializable

@Serializable
data class LicenseReport(
    val dependencies: List<LicenseReportDependency>
)

@Serializable
data class LicenseReportDependency(
    val moduleName: String? = null,
    val moduleUrl: String? = null,
    val moduleArtifactId: String? = null,
    val moduleVersion: String? = null,
    val moduleLicense: String? = null,
    val moduleLicenseUrl: String? = null
)