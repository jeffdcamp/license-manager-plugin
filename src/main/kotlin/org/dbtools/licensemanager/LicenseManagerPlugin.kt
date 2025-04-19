package org.dbtools.licensemanager

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/**
 * LicenseManager plugin
 */
class LicenseManagerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val licenseManagerExtension = project.extensions.create<LicenseManagerExtension>(
            PLUGIN_EXTENSION_NAME,
            "${project.buildDir}/licenses",
            "${project.buildDir}/licenses-working"
        )

        project.tasks.register<ReportTask>("createLicenseReports", licenseManagerExtension)
    }

    companion object {
        const val PLUGIN_EXTENSION_NAME = "licenseManager"
    }
}