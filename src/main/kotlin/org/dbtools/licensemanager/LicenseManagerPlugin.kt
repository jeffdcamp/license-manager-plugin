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
import java.io.File

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
        val parentPomFileCollection = createParentPomFileCollection(project, pomFileCollection)

        project.tasks.register<ReportTask>("createLicenseReports") {
            pomFiles.from(pomFileCollection)
            parentPomFiles.from(parentPomFileCollection)

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

    /**
     * Resolve the `<parent>` chain of [pomFiles] (up to [MAX_PARENT_DEPTH] levels) and return all parent pom.xml files.
     *
     * Parent coordinates are only known after a pom has been downloaded, so each level's dependencies are supplied
     * lazily from the files of the level below. Like [createPomFileCollection], nothing is resolved unless the report
     * task actually runs, and only configuration cache safe objects are captured.
     *
     * A configuration can only hold one version of a module (conflict resolution would turn
     * `guava-parent:26.0-android` into `guava-parent:33.4.0-jre`), so every level is split into
     * [PARENT_VERSION_SLOTS] configurations: slot N holds the Nth version of each parent module.
     */
    private fun createParentPomFileCollection(project: Project, pomFiles: FileCollection): FileCollection {
        val dependencyFactory = project.dependencyFactory
        val allParentPomFiles = project.objects.fileCollection()

        var childPomFiles: FileCollection = pomFiles
        for (level in 1..MAX_PARENT_DEPTH) {
            val parentCoordinatesBySlot = childPomFiles.elements.map { locations ->
                locations
                    .mapNotNull { location -> readParentCoordinates(location.asFile) }
                    .distinct()
                    .groupBy { it.substringBeforeLast(':') } // group:artifact
                    .values
                    .flatMap { versions -> versions.sorted().withIndex() }
                    .groupBy({ it.index }, { it.value })
            }

            val levelPomFiles = project.objects.fileCollection()
            for (slot in 0 until PARENT_VERSION_SLOTS) {
                val parentConfiguration = project.configurations.create("$PARENT_POM_CONFIGURATION_PREFIX${level}Slot$slot") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    isTransitive = false
                }
                parentConfiguration.dependencies.addAllLater(
                    parentCoordinatesBySlot.map { bySlot ->
                        bySlot[slot].orEmpty().map { coordinates -> dependencyFactory.create("$coordinates@pom") }
                    }
                )
                levelPomFiles.from(parentConfiguration.incoming.artifactView { isLenient = true }.files)
            }

            allParentPomFiles.from(levelPomFiles)
            childPomFiles = levelPomFiles
        }

        return allParentPomFiles
    }

    private fun readParentCoordinates(pomFile: File): String? =
        try {
            PomResolver.parentCoordinates(PomResolver.parse(pomFile))
        } catch (@Suppress("SwallowedException") e: Exception) {
            null // ReportTask reports unparseable poms
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
        private const val PARENT_POM_CONFIGURATION_PREFIX = "pomParents"

        /** deep enough for real-world chains (e.g. module -> project parent -> org parent -> apache parent) */
        private const val MAX_PARENT_DEPTH = 5

        /** max number of versions of the same parent module per level (e.g. guava-parent 26.0-android + 33.4.0-jre) */
        private const val PARENT_VERSION_SLOTS = 4
    }
}
