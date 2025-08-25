package org.dbtools.licensemanager

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.*
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

    private val okHttpClient = OkHttpClient()

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
        getOutputDirs(extension.summaryDirs).forEach { dir ->
            generateSummaryReportHtml(poms, File(dir, "license-summary.html"))
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
        var allPoms: List<Pom> = artifacts.mapNotNull { resolvedArtifact ->
            val pomFile = resolvedArtifact.file
            logger.info("Parsing pom.xml for artifact: ${pomFile.absoluteFile}")
            try {
                xmlMapper.readValue(pomFile, Pom::class.java)
            } catch (e: Exception) {
                logger.error("ERROR: Failed to parse pom file: [${pomFile.absoluteFile}]... (error: ${e.message})... skipping...")
                null
            }
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
                    val configuration: Configuration = configurations.getByName(POM_CONFIGURATION);
                    val dependency: Dependency? = project.dependencies.add(POM_CONFIGURATION, gav)
                    if (dependency != null) {
                        configuration.dependencies.add(dependency)
                    }
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

        extension.customLicenses.forEach { customText ->
            val customTextHtml = buildString {
                appendLine("<p>")
                appendLine(customText)
                appendLine("</p>")
                appendLine("<hr/>")
            }
            dependenciesHtml.append(customTextHtml)
        }

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
                    "    <strong>License: </strong>${license.name} - ${
                        if (license.url != null) "<a href='${
                            formatUrl(
                                license.url
                            )
                        }'>${formatUrl(license.url)}" else ""
                    }</a>"
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
                |<!DOCTYPE html>
                |<html lang="en">
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

    private fun generateSummaryReportHtml(poms: List<Pom>, outputFile: File) {
        val dependenciesHtml = StringBuffer()

        // Key = License name
        val allPoms = mutableMapOf<String, MutableList<Pom>>()
        val pomLicenses = mutableMapOf<String, PomLicense>()

        poms.forEach { pom ->
            pom.licenses?.forEach { pomLicense ->
                val key = pomLicense.url?.replace("http:", "https:") ?: pomLicense.name
                if (key != null) {
                    pomLicenses[key] = pomLicense

                    if (allPoms.contains(key)) {
                        allPoms[key]?.add(pom)
                    } else {
                        allPoms[key] = mutableListOf(pom)
                    }
                }
            }
        }

        allPoms.keys.forEach { key ->
            val dependencyHtml = buildString {
                appendLine("<p>")
                val license = pomLicenses[key]
                appendLine("    <strong>${license?.name ?: "UNKNOWN LICENSE"}</strong><br/>")

                val url = license?.url
                if (url != null) {
                    appendLine("    $url<br/>")
                }
                appendLine("count: ${allPoms[key]?.size ?: 0}")

                appendLine("<ul>")
                allPoms[key]?.forEach { pom ->
                    if (!pom.name.isNullOrBlank()) {
                        appendLine("<li>${pom.groupId}:${pom.artifactId} (${pom.name})</li>")
                    } else {
                        appendLine("<li>${pom.groupId}:${pom.artifactId}</li>")
                    }
                }
                appendLine("</ul>")
                appendLine("</p>")
                appendLine("<hr/>")
            }
            dependenciesHtml.append(dependencyHtml)
        }

        @Language("HTML")
        val html = """
                |<!DOCTYPE html>
                |<html lang="en">
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

        // verify that there are not any blocked licenses
        checkForBlockedLicense(pomLicenses.values, outputFile)
    }

    private fun checkForBlockedLicense(pomLicenseByLicenseName: Collection<PomLicense>, outputFile: File) {
        val remoteInvalidLicenses = if (!extension.invalidLicensesUrl.isNullOrBlank()) {
            downloadAndReadInvalidLicensesUrl().orEmpty()
        } else {
            emptyList()
        }

        val buildConfigInvalidLicenses = extension.invalidLicenses
        val blockList = remoteInvalidLicenses + buildConfigInvalidLicenses

        if (remoteInvalidLicenses.isEmpty() && buildConfigInvalidLicenses.isEmpty()) {
            return
        }

        val foundViolation = pomLicenseByLicenseName.firstOrNull { pomLicense  ->
            blockList.firstOrNull { blockListItem ->
                pomLicense.name.orEmpty().contains(blockListItem)
            } != null
        }
        if (foundViolation != null) {
            error("ERROR: [$foundViolation] is an INVALID license.  See ${outputFile.absolutePath}")
        }
    }

    private fun downloadAndReadInvalidLicensesUrl(): List<String>? {
        // check to see if we have an url
        val invalidLicensesUrl = extension.invalidLicensesUrl ?: return null
        if (invalidLicensesUrl.isBlank()) {
            return null
        }

        // setup local cache file
        val fileSystem = FileSystem.SYSTEM
        val workingInvalidDir: Path = extension.invalidLicensesWorkingDir.toPath()
        val workingInvalidFile: Path = workingInvalidDir / "invalid-licenses-cache.json"

        // try to read config from local file (cache), if it exists
        if (fileSystem.exists(workingInvalidFile)) {
            val contentTxt = fileSystem.read(workingInvalidFile) { readUtf8() }
            return json.decodeFromString<List<String>>(contentTxt)
        }

        // local cache file does not exist, try to read from url
        val request = Request.Builder()
            .url(invalidLicensesUrl)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download invalidLicensesUrl file ($response)")
            val bodyText = response.body?.string() ?: return null

            val data = json.decodeFromString<List<String>>(bodyText)

            // save to local cache
            fileSystem.createDirectories(workingInvalidDir)
            fileSystem.write(workingInvalidFile) { writeUtf8(bodyText) }

            return data
        }
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
