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
    var outputDir: String = defaultOutputDir

    /**
     * Default output directory
     */
    @get:Optional
    @get:Input
    var outputDirs: List<String> = listOf(outputDir)

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
}