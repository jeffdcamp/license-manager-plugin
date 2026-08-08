package org.dbtools.licensemanager

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/**
 * LicenseManager plugin
 */
class LicenseManagerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<LicenseManagerExtension>(PLUGIN_EXTENSION_NAME)

        val defaultOutputDir = project.layout.buildDirectory.dir("licenses").map { listOf(it.asFile.path) }

        extension.outputDirs.convention(defaultOutputDir)
        extension.summaryDirs.convention(defaultOutputDir)
        extension.outputFilename.convention("licenses")
        extension.createHtmlReport.convention(true)
        extension.createJsonReport.convention(false)
        extension.createCsvReport.convention(false)
        extension.invalidLicensesWorkingDir.convention(
            project.layout.buildDirectory.dir("licenses-working").map { it.asFile.path }
        )

        val projectDir = project.layout.projectDirectory

        // must be created eagerly: it registers an afterEvaluate action, which is illegal once the
        // configuration phase has moved on to realizing tasks
        val pomFileCollection = createPomFileCollection(project, extension)

        project.tasks.register<ReportTask>("createLicenseReports") {
            pomFiles.from(pomFileCollection)

            excludeArtifactIds.convention(extension.excludeArtifactIds)
            excludeGroups.convention(extension.excludeGroups)
            outputFilename.convention(extension.outputFilename)
            createHtmlReport.convention(extension.createHtmlReport)
            createJsonReport.convention(extension.createJsonReport)
            createCsvReport.convention(extension.createCsvReport)
            customLicenses.convention(extension.customLicenses)
            invalidLicenses.convention(extension.invalidLicenses)
            invalidLicensesUrl.convention(extension.invalidLicensesUrl)

            // Directory.dir() resolves relative paths against the project directory and leaves absolute paths alone
            reportDirs.from(extension.outputDirs.map { paths -> paths.map { projectDir.dir(it) } })
            summaryDirs.from(extension.summaryDirs.map { paths -> paths.map { projectDir.dir(it) } })
            invalidLicensesWorkingDir.set(extension.invalidLicensesWorkingDir.map { projectDir.dir(it) })
        }
    }

    /**
     * Create a resolvable "pom" configuration containing the pom.xml artifact of every module on the
     * project's runtime classpath.
     *
     * The dependencies are added via [org.gradle.api.DomainObjectCollection.addAllLater] so that nothing
     * is resolved unless the report task actually runs. The provider only captures a
     * [Provider] of [ResolvedComponentResult] and a [org.gradle.api.artifacts.dsl.DependencyFactory]
     * (both configuration cache safe) - it never captures the [Project].
     */
    private fun createPomFileCollection(project: Project, extension: LicenseManagerExtension): FileCollection {
        val pomConfiguration = project.configurations.create(POM_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            isVisible = false
            // each pom is requested explicitly, so there is nothing to walk transitively
            isTransitive = false
        }

        val dependencyFactory = project.dependencyFactory

        // The configuration to inspect depends on extension.variantName, which is not known until the
        // build script has been evaluated. afterEvaluate still runs during the configuration phase, so it
        // is configuration cache compatible.
        project.afterEvaluate {
            val runtimeClasspath = findRuntimeClasspath(project, extension.variantName.orNull)
            if (runtimeClasspath == null) {
                project.logger.warn(
                    "LicenseManager: unable to find a resolvable runtime classpath configuration for project " +
                        "[${project.path}]. No licenses will be reported."
                )
                return@afterEvaluate
            }

            val rootComponent: Provider<ResolvedComponentResult> = runtimeClasspath.incoming.resolutionResult.rootComponent

            pomConfiguration.dependencies.addAllLater(
                rootComponent.map { root ->
                    collectModuleIds(root).map { id -> dependencyFactory.create("$id@pom") }
                }
            )
        }

        // lenient so that a module without a published pom does not fail the build
        return pomConfiguration.incoming.artifactView { isLenient = true }.files
    }

    private fun findRuntimeClasspath(project: Project, variantName: String?): Configuration? {
        val candidateNames = buildList {
            // Android projects have one runtime classpath per variant
            if (!variantName.isNullOrBlank()) add("${variantName}RuntimeClasspath")
            add("runtimeClasspath")
        }

        return candidateNames
            .mapNotNull { project.configurations.findByName(it) }
            .firstOrNull { it.isCanBeResolved }
    }

    /**
     * Walk the resolved dependency graph and collect the "group:name:version" of every external module.
     */
    private fun collectModuleIds(root: ResolvedComponentResult): List<String> {
        val moduleIds = linkedSetOf<String>()
        val visited = hashSetOf<ResolvedComponentResult>()

        fun visit(component: ResolvedComponentResult) {
            if (!visited.add(component)) return

            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { dependencyResult ->
                    val selected = dependencyResult.selected
                    // local project dependencies have no pom to read
                    (selected.id as? ModuleComponentIdentifier)?.let { id ->
                        moduleIds.add("${id.group}:${id.module}:${id.version}")
                    }
                    visit(selected)
                }
        }

        visit(root)

        return moduleIds.toList()
    }

    companion object {
        const val PLUGIN_EXTENSION_NAME = "licenseManager"
        private const val POM_CONFIGURATION = "pom"
    }
}
