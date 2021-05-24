package org.dbtools.licensemanager

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.intellij.lang.annotations.Language
import java.io.File
import javax.inject.Inject

/**
 * LicenseManager Report Task
 */
open class ReportTask @Inject constructor(
    @get:Nested val extension: LicenseManagerExtension,
) : DefaultTask() {
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        useArrayPolymorphism = true
    }

    init {
        group = "License"
        description = "Creates reports/files for licences"
    }

    @TaskAction
    fun execute() {
        val poms = readPoms(false)
        getOutputDirs(extension.outputDirs).forEach { dir ->
            // Reports
            if (extension.createHtmlReport) {
                generateLicenceReportHtml(poms, File(dir, "${extension.outputFilename}.html"))
            }
            if (extension.createJsonReport) {
                generateLicenceReportJson(poms, File(dir, "${extension.outputFilename}.json"))
            }
            if (extension.createCsvReport) {
                generateLicenceReportCsv(poms, File(dir, "${extension.outputFilename}.csv"))
            }
        }
    }

    private fun readPoms(filterTransitive: Boolean): List<Pom> {
        // setup pom configuration
        createPomConfiguration()

        // add all dependencies to pom configuration
        findAllDependencies()

        // get created pom configuration
        val configuration = project.configurations
            .getByName(POM_CONFIGURATION)
            .resolvedConfiguration

        // get all artifacts
        val artifacts = configuration.lenientConfiguration.artifacts

        logger.info("Found Artifact Dependencies count: ${artifacts.size}")

        // read/parse all pom.xml files
        val xmlMapper = XmlMapper()
        var allPoms = artifacts.map { resolvedArtifact ->
            val pomFile = resolvedArtifact.file
            xmlMapper.readValue(pomFile, Pom::class.java)
        }

        // filters
        if (filterTransitive) {
            allPoms = filterTransitiveDependencies(configuration, allPoms)
        }
        if (extension.excludeGroups.isNotEmpty()) {
            allPoms = filterExcludedGroups(allPoms)
        }

        if (extension.excludeArtifactIds.isNotEmpty()) {
            allPoms = filterExcludedArtifactIds(allPoms)
        }

        // sort
        allPoms = allPoms.sortedBy { it.name ?: it.artifactId }

        return allPoms
    }


    /**
     * Exclude specified GroupIds
     */
    private fun filterExcludedGroups(allPoms: List<Pom>): List<Pom> {
        val filterGroups = extension.excludeGroups
        return allPoms.filter { pom ->
            !filterGroups.contains(pom.groupId)
        }
    }

    /**
     * Exclude specified GroupIds
     */
    private fun filterExcludedArtifactIds(allPoms: List<Pom>): List<Pom> {
        val filterArtifactIds = extension.excludeArtifactIds
        return allPoms.filter { pom ->
            !filterArtifactIds.contains(pom.artifactId)
        }
    }

    /**
     * Work-in-progress....
     */
    private fun filterTransitiveDependencies(configuration: ResolvedConfiguration, allPoms: List<Pom>): List<Pom> {
        val firstLevelModuleDependencies: MutableSet<ResolvedDependency> = configuration.firstLevelModuleDependencies

        firstLevelModuleDependencies.forEach { dependency ->
            println("   ${dependency.module.id}  / ${dependency.moduleName}")

        }

        return allPoms
            .filter { pom ->
//                println("     ${pom.artifactId}")
                firstLevelModuleDependencies.any { dependency ->
                    pom.artifactId == dependency.moduleName && pom.groupId == dependency.moduleGroup && pom.version == dependency.moduleVersion
                }
            }
    }

    private fun createPomConfiguration() {
        project.configurations.apply {
            create(POM_CONFIGURATION)
            forEach { configuration ->
                try {
                    configuration.isCanBeResolved = true
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /**
     * Go through all configurations and add dependencies
     */
    private fun findAllDependencies() {
        val configurationSet = linkedSetOf<Configuration>()
        val configurations = project.configurations

        // add "compile" configuration older java and android gradle plugins
        configurations.find { it.name == "compile" }?.let {
            configurationSet.add(configurations.getByName("compile"))
        }

        // add "api" and "implementation" configurations for newer java-library and android gradle plugins
        configurations.find { it.name == "api" }?.let {
            configurationSet.add(configurations.getByName("api"))
        }
        configurations.find { it.name == "implementation" }?.let {
            configurationSet.add(configurations.getByName("implementation"))
        }

        // Android project configuration
        extension.variantName?.let { variant ->
            configurations.find { it.name == "${variant}RuntimeClasspath" }?.also {
                configurationSet.add(it)
            }
        }

        // cycle through all configuration dependencies
        configurationSet.forEach { configuration ->
            if (configuration.isCanBeResolved) {
                val allDeps = configuration.resolvedConfiguration.lenientConfiguration.allModuleDependencies
                getResolvedArtifactsFromResolvedDependencies(allDeps).forEach { artifact ->
                    val id = artifact.moduleVersion.id
                    val gav = "${id.group}:${id.name}:${id.version}@pom"
                    configurations.getByName(POM_CONFIGURATION).dependencies.add(
                        project.dependencies.add(POM_CONFIGURATION, gav)
                    )
                }
            }
        }
    }

    private fun getResolvedArtifactsFromResolvedDependencies(resolvedDependencies: Set<ResolvedDependency>): Set<ResolvedArtifact> {
        val resolvedArtifacts = hashSetOf<ResolvedArtifact>()
        resolvedDependencies.forEach { resolvedDependency ->
            try {
                if (resolvedDependency.moduleVersion == "unspecified") {
                    /**
                     * Attempting to getAllModuleArtifacts on a local library project will result
                     * in AmbiguousVariantSelectionException as there are not enough criteria
                     * to match a specific variant of the library project. Instead we skip the
                     * the library project itself and enumerate its dependencies.
                     */
                    resolvedArtifacts.addAll(getResolvedArtifactsFromResolvedDependencies(resolvedDependency.children))
                } else {
                    resolvedArtifacts.addAll(resolvedDependency.allModuleArtifacts)
                }
            } catch (e: Exception) {
                logger.warn("Failed to process $resolvedDependency.name", e)
            }
        }
        return resolvedArtifacts
    }

    private fun formatUrl(url: String?): String? {
        // # can cause issues with rendering in mobile webview (also only seemed to be used to jump to a specific version of an artifact)
        return url?.substringBeforeLast("#")
    }

    private fun formatCsvName(name: String?): String? {
        return if (name?.contains(",") == true) "\"$name\"" else name
    }

    private fun generateLicenceReportHtml(poms: List<Pom>, outputFile: File) {
        val dependenciesHtml = StringBuffer()
        poms.forEach { pom ->
            @Suppress("MaxLineLength") // html
            @Language("HTML")
            val dependencyHtml = buildString {
                appendLine("<p>")
                appendLine("    <strong>${pom.name ?: pom.artifactId}</strong><br/>")
                pom.url?.let {
                    appendLine("    <strong>URL: </strong><a href='${formatUrl(it)}'>${formatUrl(it)}</a><br/>")
                }
                val licenses = pom.licenses?.joinToString(separator = "<br/>") { license ->
                    "    <strong>License: </strong>${license.name} - ${if (license.url != null) "<a href='${formatUrl(license.url)}'>${formatUrl(license.url)}" else ""}</a>"
                }
                licenses?.let { appendLine(it) }
                appendLine("</p>")
                appendLine("<hr/>")
            }
            dependenciesHtml.append(dependencyHtml)
        }

        @Suppress("UnnecessaryVariable")
        @Language("HTML")
        val html = """
                |<html>
                |    <style>
                |        a { word-wrap: break-word;}
                |        strong { word-wrap: break-word;}
                |    </style>
                |    <body>
                |        $dependenciesHtml
                |    </body>
                |</html>
                |""".trimMargin()

        // remove existing file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // write
        outputFile.writeText(html)
    }

    private fun generateLicenceReportJson(poms: List<Pom>, outputFile: File) {
        val dependencies = mutableListOf<LicenseReportDependency>()

        poms.forEach { pom ->
            val license = pom.licenses?.firstOrNull()

            val formattedPomUrl = formatUrl(pom.url)

            dependencies.add(
                LicenseReportDependency(
                    moduleName = pom.name ?: pom.artifactId,
                    moduleUrl = formattedPomUrl,
                    moduleGroupId = pom.groupId,
                    moduleArtifactId = pom.artifactId,
                    moduleVersion = pom.version,
                    moduleLicense = license?.name,
                    moduleLicenseUrl = license?.url
                )
            )
        }

        val licenseReport = LicenseReport(dependencies)

        val json = json.encodeToString(LicenseReport.serializer(), licenseReport)

        // remove existing file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // write
        outputFile.writeText(json)
    }

    private fun generateLicenceReportCsv(poms: List<Pom>, outputFile: File) {
        val dependenciesCsv = StringBuffer()
        poms.forEach { pom ->
            val license = pom.licenses?.firstOrNull()

            val formattedPomUrl = formatUrl(pom.url)

            dependenciesCsv
                .append(formatCsvName(pom.name ?: pom.artifactId ?: "")).append(',')
                .append(formattedPomUrl ?: "").append(',')
                .append(pom.groupId ?: "").append(',')
                .append(pom.artifactId ?: "").append(',')
                .append(pom.version ?: "").append(',')
                .append(formatCsvName(license?.name ?: "")).append(',')
                .append(license?.url ?: "")
                .append('\n')
        }

        // remove existing file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // write
        outputFile.writeText(dependenciesCsv.toString())
    }

    private fun getOutputDirs(pathnames: List<String>): List<File> {
        return pathnames.map { getOutputDir(it) }
    }

    private fun getOutputDir(pathname: String): File {
        // project.file is required for gradle to be able to create directories. (./gradlew clean createLicenses)
        val outputDir = project.file(pathname)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        check(outputDir.exists()) { "Failed to create output directory: [${pathname}]" }

        return outputDir
    }

    companion object {
        private const val POM_CONFIGURATION = "pom"
    }
}