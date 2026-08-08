package org.dbtools.licensemanager

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Extension for LicenseManager plugin
 *
 * All values are lazy [Property]/[ListProperty] instances so that the plugin never has to read them
 * eagerly while the build is still being configured. This is what allows [ReportTask] to be wired up
 * with configuration cache compatible inputs.
 */
abstract class LicenseManagerExtension {
    abstract val excludeArtifactIds: ListProperty<String>

    abstract val excludeGroups: ListProperty<String>

    /**
     * Output directories for the reports. Default: <buildDir>/licenses
     */
    abstract val outputDirs: ListProperty<String>

    /**
     * Output directories for the license summary. Default: <buildDir>/licenses
     */
    abstract val summaryDirs: ListProperty<String>

    /**
     * Name of file without extension
     */
    abstract val outputFilename: Property<String>

    /**
     * Android variant whose runtime classpath should be inspected (example: "release").
     * When unset, the project's "runtimeClasspath" configuration is used.
     */
    abstract val variantName: Property<String>

    abstract val createHtmlReport: Property<Boolean>

    abstract val createJsonReport: Property<Boolean>

    abstract val createCsvReport: Property<Boolean>

    abstract val customLicenses: ListProperty<String>

    /**
     * List of text that will break the build if the license name contains these key words
     */
    abstract val invalidLicenses: ListProperty<String>

    abstract val invalidLicensesWorkingDir: Property<String>

    /**
     * URL path to json file containing Json array of text that will break the build if the license name contains these keywords
     * File content Example: ["GPL","GNU"]
     */
    abstract val invalidLicensesUrl: Property<String>
}
