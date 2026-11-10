package org.dbtools.licensemanager

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import java.io.File

/**
 * Builds an "effective" [Pom] the way Maven does, limited to the fields the reports use:
 *
 * - values missing from a pom are inherited from its `<parent>` chain (groupId, version, description, url,
 *   licenses, organization, developers, scm, properties)
 * - `${...}` placeholders (`${project.groupId}`, `${project.artifactId}`, custom `<properties>`, ...) are resolved
 *   against the effective pom
 *
 * Example: `org.brotli:dec:0.1.2` declares no groupId, url or licenses and uses
 * `<name>${project.groupId}:${project.artifactId}</name>`; everything else comes from `org.brotli:parent:0.1.2`.
 */
object PomResolver {
    private const val MAX_INTERPOLATION_DEPTH = 10
    private val PLACEHOLDER = Regex("""\$\{([^}]+)}""")

    private val xmlMapper: XmlMapper = XmlMapper().apply {
        // an empty <properties/> or <licenses/> element must not fail the whole pom
        enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
    }

    fun parse(pomFile: File): Pom = xmlMapper.readValue(pomFile, Pom::class.java)

    /**
     * "groupId:artifactId:version" of [pom], taking groupId/version from `<parent>` when the pom itself omits them.
     */
    fun coordinates(pom: Pom): String? {
        val groupId = pom.groupId ?: pom.parent?.groupId ?: return null
        val artifactId = pom.artifactId ?: return null
        val version = pom.version ?: pom.parent?.version ?: return null
        return "$groupId:$artifactId:$version"
    }

    fun parentCoordinates(pom: Pom): String? {
        val parent = pom.parent ?: return null
        if (parent.groupId == null || parent.artifactId == null || parent.version == null) return null
        return "${parent.groupId}:${parent.artifactId}:${parent.version}"
    }

    /**
     * Index parent poms by their coordinates for use with [resolve].
     */
    fun indexByCoordinates(poms: Collection<Pom>): Map<String, Pom> =
        poms.mapNotNull { pom -> coordinates(pom)?.let { it to pom } }.toMap()

    fun resolve(pom: Pom, parentsByCoordinates: Map<String, Pom>): Pom =
        interpolate(inherit(pom, parentsByCoordinates, mutableSetOf()))

    private fun inherit(pom: Pom, parentsByCoordinates: Map<String, Pom>, visited: MutableSet<String>): Pom {
        val parentRef = pom.parent
        val parent = parentCoordinates(pom)
            ?.takeIf { visited.add(it) } // guard against a (broken) parent cycle
            ?.let { parentsByCoordinates[it] }
            ?.let { inherit(it, parentsByCoordinates, visited) }

        return pom.copy(
            // groupId/version come from the <parent> element even if the parent pom itself could not be resolved
            groupId = pom.groupId ?: parentRef?.groupId ?: parent?.groupId,
            version = pom.version ?: parentRef?.version ?: parent?.version,
            description = pom.description ?: parent?.description,
            // Maven appends "/artifactId" to an inherited url; that usually produces a dead link, so keep it as-is
            url = pom.url ?: parent?.url,
            licenses = pom.licenses.takeUnless { it.isNullOrEmpty() } ?: parent?.licenses,
            organization = pom.organization ?: parent?.organization,
            developers = pom.developers.takeUnless { it.isNullOrEmpty() } ?: parent?.developers,
            scm = pom.scm ?: parent?.scm,
            properties = parent?.properties.orEmpty() + pom.properties.orEmpty(),
        )
    }

    private fun interpolate(pom: Pom): Pom {
        val values = buildMap {
            pom.properties?.forEach { (key, value) -> if (value is String) put(key, value) }
            // the model wins over a same named custom property
            for (prefix in listOf("project.", "pom.", "")) {
                pom.groupId?.let { put("${prefix}groupId", it) }
                pom.artifactId?.let { put("${prefix}artifactId", it) }
                pom.version?.let { put("${prefix}version", it) }
            }
            for (prefix in listOf("project.", "pom.")) {
                pom.name?.let { put("${prefix}name", it) }
                pom.description?.let { put("${prefix}description", it) }
                pom.url?.let { put("${prefix}url", it) }
            }
            pom.parent?.let { parent ->
                for (prefix in listOf("project.parent.", "parent.")) {
                    parent.groupId?.let { put("${prefix}groupId", it) }
                    parent.artifactId?.let { put("${prefix}artifactId", it) }
                    parent.version?.let { put("${prefix}version", it) }
                }
            }
        }

        fun expand(text: String?, depth: Int = 0): String? {
            if (text == null || depth > MAX_INTERPOLATION_DEPTH || !text.contains("\${")) return text
            return PLACEHOLDER.replace(text) { match ->
                values[match.groupValues[1]]?.let { expand(it, depth + 1) } ?: match.value
            }
        }

        // a value that still holds a placeholder is not worth showing (name falls back to artifactId)
        fun expandOrNull(text: String?): String? = expand(text)?.takeUnless { it.contains("\${") }

        return pom.copy(
            name = expandOrNull(pom.name),
            description = expand(pom.description),
            url = expandOrNull(pom.url),
            licenses = pom.licenses?.map { it.copy(name = expand(it.name), url = expandOrNull(it.url)) },
            organization = pom.organization?.let { it.copy(name = expand(it.name), url = expandOrNull(it.url)) },
            developers = pom.developers?.map { it.copy(name = expand(it.name)) },
            scm = pom.scm?.let { it.copy(url = expandOrNull(it.url)) },
        )
    }
}
