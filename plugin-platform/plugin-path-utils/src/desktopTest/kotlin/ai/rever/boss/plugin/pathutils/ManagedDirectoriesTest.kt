package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManagedDirectoriesTest {
    @TempDir
    lateinit var temporary: File

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `createOwnerOnlyDir creates an owner-only directory`() {
        assumeTrue(!isWindows, "POSIX permissions do not apply on Windows")

        val dir = File(temporary, "managed")
        ManagedDirectories.createOwnerOnlyDir(dir)

        assertTrue(dir.isDirectory)
        assertEquals(ownerOnly(), Files.getPosixFilePermissions(dir.toPath()))
    }

    @Test
    fun `createOwnerOnlyDir adopts and locks down an existing directory`() {
        assumeTrue(!isWindows, "POSIX permissions do not apply on Windows")

        val dir = File(temporary, "managed").apply { mkdirs() }
        ManagedDirectories.createOwnerOnlyDir(dir)

        assertEquals(ownerOnly(), Files.getPosixFilePermissions(dir.toPath()))
    }

    @Test
    fun `createOwnerOnlyDir refuses a symlinked directory`() {
        assumeTrue(!isWindows, "Symlink creation is not available on Windows CI")

        val real = File(temporary, "real").apply { mkdirs() }
        val link = File(temporary, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        assertFailsWith<SecurityException> {
            ManagedDirectories.createOwnerOnlyDir(link)
        }
    }

    @Test
    fun `listContainedRegularFiles returns in-root regular files`() {
        val dir = File(temporary, "plugins").apply { mkdirs() }
        val jar = File(dir, "plugin.jar").apply { writeText("jar") }
        File(dir, "notes.txt").apply { writeText("note") }

        val jars = ManagedDirectories.listContainedRegularFiles(dir) { it.extension == "jar" }

        assertEquals(listOf(jar.absolutePath), jars.map { it.absolutePath })
    }

    @Test
    fun `listContainedRegularFiles skips a symlinked jar escaping the root`() {
        assumeTrue(!isWindows, "Symlink creation is not available on Windows CI")

        val dir = File(temporary, "plugins").apply { mkdirs() }
        val outside = File(temporary, "evil.jar").apply { writeText("evil") }
        val real = File(dir, "real.jar").apply { writeText("jar") }
        Files.createSymbolicLink(File(dir, "evil.jar").toPath(), outside.toPath())

        val jars = ManagedDirectories.listContainedRegularFiles(dir) { it.extension == "jar" }

        assertEquals(listOf(real.absolutePath), jars.map { it.absolutePath })
    }

    @Test
    fun `listContainedRegularFiles returns empty for a symlinked directory`() {
        assumeTrue(!isWindows, "Symlink creation is not available on Windows CI")

        val real = File(temporary, "real-plugins").apply { mkdirs() }
        File(real, "plugin.jar").apply { writeText("jar") }
        val link = File(temporary, "plugins")
        Files.createSymbolicLink(link.toPath(), real.toPath())

        val jars = ManagedDirectories.listContainedRegularFiles(link) { it.extension == "jar" }

        assertTrue(jars.isEmpty())
    }

    @Test
    fun `listContainedRegularFiles returns empty for a missing directory`() {
        val jars =
            ManagedDirectories.listContainedRegularFiles(File(temporary, "missing")) {
                it.extension == "jar"
            }

        assertTrue(jars.isEmpty())
    }

    private fun ownerOnly(): Set<PosixFilePermission> =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
}
