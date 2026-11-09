package org.dbtools.licensemanager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class PomResolverTest {
    private fun pom(name: String): Pom =
        PomResolver.parse(File(requireNotNull(javaClass.getResource("/poms/$name")).toURI()))

    private val exampleParents = PomResolver.indexByCoordinates(listOf(pom("grandparent.pom"), pom("middle.pom")))

    @Test
    fun `brotli dec inherits from its parent and resolves its name`() {
        val parents = PomResolver.indexByCoordinates(listOf(pom("parent-0.1.2.pom")))

        val resolved = PomResolver.resolve(pom("dec-0.1.2.pom"), parents)

        assertEquals("org.brotli:dec", resolved.name)
        assertEquals("org.brotli", resolved.groupId)
        assertEquals("dec", resolved.artifactId)
        assertEquals("0.1.2", resolved.version)
        assertEquals("http://brotli.org", resolved.url)
        assertEquals(listOf(PomLicense("MIT License", "http://www.opensource.org/licenses/mit-license.php")), resolved.licenses)
    }

    @Test
    fun `values are inherited across a two level parent chain and interpolated in the child context`() {
        val resolved = PomResolver.resolve(pom("child.pom"), exampleParents)

        assertEquals("Child of middle", resolved.name)
        assertEquals("com.example", resolved.groupId)
        assertEquals("1.0", resolved.version)
        assertEquals("https://example.com", resolved.url)
        // license.version comes from middle, license.host from grandparent, artifactId from the child itself
        assertEquals(listOf(PomLicense("Example License 2.0", "https://licenses.example.com/child")), resolved.licenses)
    }

    @Test
    fun `child licenses replace parent licenses`() {
        val resolved = PomResolver.resolve(pom("child-own-license.pom"), exampleParents)

        assertEquals(listOf(PomLicense("Child License", "https://child.example.com/license")), resolved.licenses)
        assertEquals("https://example.com", resolved.url)
    }

    @Test
    fun `missing parent pom still supplies coordinates and unresolved name is dropped`() {
        val resolved = PomResolver.resolve(pom("orphan.pom"), emptyMap())

        assertEquals("com.missing", resolved.groupId)
        assertEquals("3.1", resolved.version)
        assertNull(resolved.name)
        assertNull(resolved.licenses)
    }

    @Test
    fun `self referencing placeholder does not loop`() {
        val resolved = PomResolver.resolve(Pom(artifactId = "loop", name = "\${project.name}"), emptyMap())

        assertNull(resolved.name)
    }
}
