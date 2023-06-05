package org.dbtools.licensemanager

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import javax.inject.Inject

/**
 * Extension for LicenseManager plugin
 */
open class LicenseManagerExtension @Inject constructor(
    defaultOutputDir: String
) {
    @get:Optional
    @get:Input
    var excludeArtifactIds: List<String> = emptyList()

    @get:Optional
    @get:Input
    var excludeGroups: List<String> = emptyList()

    /**
     * Default output directory
     */
    @get:Optional
    @get:Input
    var outputDirs: List<String> = listOf(defaultOutputDir)

    /**
     * Default summary directory
     */
    @get:Optional
    @get:Input
    var summaryDirs: List<String> = listOf(defaultOutputDir)

    /**
     * Name of file without extension
     */
    @get:Input
    var outputFilename: String = "licenses"

    @get:Optional
    @get:Input
    var variantName: String? = null

    @get:Input
    var createHtmlReport: Boolean = true

    @get:Input
    var createJsonReport: Boolean = false

    @get:Input
    var createCsvReport: Boolean = false

    @get:Optional
    @get:Input
    var customLicenses: List<String> = emptyList()

    /**
     * List of text that will break the build if the license name contains these key words
     */
    @get:Optional
    @get:Input
    var invalidLicenses: List<String> = emptyList()
}