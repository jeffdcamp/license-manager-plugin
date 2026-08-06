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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.intellij.lang.annotations.Language
import java.io.File

/**
 * LicenseManager Report Task
 *
 * Every input is a lazy property supplied by [LicenseManagerPlugin]; the task never touches
 * [org.gradle.api.Project] at execution time, which is what makes it configuration cache compatible.
 */
@DisableCachingByDefault(because = "Reads a remote invalid-license list, so results are not reproducible from inputs alone")
abstract class ReportTask : DefaultTask() {

    /**
     * pom.xml of every module on the runtime classpath, resolved lazily by the plugin.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pomFiles: ConfigurableFileCollection

    @get:Input
    abstract val excludeArtifactIds: ListProperty<String>

    @get:Input
    abstract val excludeGroups: ListProperty<String>

    @get:Input
    abstract val outputFilename: Property<String>

    @get:Input
    abstract val createHtmlReport: Property<Boolean>

    @get:Input
    abstract val createJsonReport: Property<Boolean>

    @get:Input
    abstract val createCsvReport: Property<Boolean>

    @get:Input
    abstract val customLicenses: ListProperty<String>

    @get:Input
    abstract val invalidLicenses: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val invalidLicensesUrl: Property<String>

    @get:OutputDirectories
    abstract val reportDirs: ConfigurableFileCollection

    @get:OutputDirectories
    abstract val summaryDirs: ConfigurableFileCollection

    /**
     * Local cache location for the downloaded [invalidLicensesUrl] content.
     */
    @get:Internal
    abstract val invalidLicensesWorkingDir: DirectoryProperty

    init {
        group = "License"
        description = "Creates reports/files for licences"
    }

    @TaskAction
    fun execute() {
        val poms = readPoms()
        val filename = outputFilename.get()

        reportDirs.files.forEach { dir ->
            dir.mkdirs()

            // Reports
            if (createHtmlReport.get()) {
                generateLicenceReportHtml(poms, File(dir, "$filename.html"))
            }
            if (createJsonReport.get()) {
                generateLicenceReportJson(poms, File(dir, "$filename.json"))
            }
            if (createCsvReport.get()) {
                generateLicenceReportCsv(poms, File(dir, "$filename.csv"))
            }
        }
        summaryDirs.files.forEach { dir ->
            dir.mkdirs()
            generateSummaryReportHtml(poms, File(dir, "license-summary.html"))
        }
    }

    private fun readPoms(): List<Pom> {
        val pomFileList = pomFiles.files

        logger.info("Found Artifact Dependencies count: ${pomFileList.size}")

        // read/parse all pom.xml files
        val xmlMapper = XmlMapper()
        var allPoms: List<Pom> = pomFileList.mapNotNull { pomFile ->
            logger.info("Parsing pom.xml for artifact: ${pomFile.absoluteFile}")
            try {
                xmlMapper.readValue(pomFile, Pom::class.java)
            } catch (e: Exception) {
                logger.error("ERROR: Failed to parse pom file: [${pomFile.absoluteFile}]... (error: ${e.message})... skipping...")
                null
            }
        }

        // filters
        val filterGroups = excludeGroups.get()
        if (filterGroups.isNotEmpty()) {
            allPoms = allPoms.filter { pom -> !filterGroups.contains(pom.groupId) }
        }

        val filterArtifactIds = excludeArtifactIds.get()
        if (filterArtifactIds.isNotEmpty()) {
            allPoms = allPoms.filter { pom -> !filterArtifactIds.contains(pom.artifactId) }
        }

        // sort
        allPoms = allPoms.sortedBy { it.name ?: it.artifactId }

        return allPoms
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

        customLicenses.get().forEach { customText ->
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

        val jsonText = json.encodeToString(LicenseReport.serializer(), licenseReport)

        // remove existing file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        // write
        outputFile.writeText(jsonText)
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
        val remoteInvalidLicenses = if (!invalidLicensesUrl.orNull.isNullOrBlank()) {
            downloadAndReadInvalidLicensesUrl().orEmpty()
        } else {
            emptyList()
        }

        val buildConfigInvalidLicenses = invalidLicenses.get()
        val blockList = remoteInvalidLicenses + buildConfigInvalidLicenses

        if (remoteInvalidLicenses.isEmpty() && buildConfigInvalidLicenses.isEmpty()) {
            return
        }

        val foundViolation = pomLicenseByLicenseName.firstOrNull { pomLicense ->
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
        val invalidLicensesUrl = invalidLicensesUrl.orNull ?: return null
        if (invalidLicensesUrl.isBlank()) {
            return null
        }

        // setup local cache file
        val fileSystem = FileSystem.SYSTEM
        val workingInvalidDir: Path = invalidLicensesWorkingDir.get().asFile.path.toPath()
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

        // Created per invocation: an OkHttpClient held in a task field cannot be stored in the configuration cache
        val okHttpClient = OkHttpClient()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to download invalidLicensesUrl file ($response)")
                val bodyText = response.body?.string() ?: return null

                val data = json.decodeFromString<List<String>>(bodyText)

                // save to local cache
                fileSystem.createDirectories(workingInvalidDir)
                fileSystem.write(workingInvalidFile) { writeUtf8(bodyText) }

                return data
            }
        } finally {
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
        }
    }

    companion object {
        // held statically so that it is never part of the task state written to the configuration cache
        private val json = Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            allowSpecialFloatingPointValues = true
            useArrayPolymorphism = true
        }
    }
}
