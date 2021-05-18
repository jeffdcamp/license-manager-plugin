package org.dbtools.licensemanager

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

/**
 * LicenseManager plugin
 */
class LicenseManagerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val licenseManagerExtension = project.extensions.create<LicenseManagerExtension>(PLUGIN_EXTENSION_NAME, "${project.buildDir}/licenses")

        project.tasks.create<ReportTask>("createLicenseReports", licenseManagerExtension)
    }

    companion object {
        const val PLUGIN_EXTENSION_NAME = "licenseManager"
    }
}