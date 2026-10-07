package org.kavo.uploader.upload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kavo.uploader.settings.ServerProfile
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RsyncSshContainerTest {
    private var container: GenericContainer<*>? = null
    private var host = "localhost"
    private var sshPort = 22
    private lateinit var root: Path

    @Before
    fun setUp() {
        startContainer()
        root = Files.createTempDirectory("rsync-ssh-test")
    }

    @After
    fun tearDown() {
        container?.stop()
        if (Files.exists(root)) {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
    }

    @Test
    fun `rsync over ssh uploads all preconfigured files identically`() {
        val service = SftpUploadService()
        val password = "pass".toByteArray()
        val profile = ServerProfile(
            name = "rsync-ssh",
            host = host,
            port = sshPort,
            username = "root",
            useRsync = true,
        )

        val flat = root.resolve("A")
        val flatTop = root.resolve("B")
        val nested = flatTop.resolve("nested")
        Files.createDirectories(flat)
        Files.createDirectories(nested)

        write(flat.resolve("readme.txt"), "hello\nworld\n")
        write(flat.resolve("data.bin"), (0..255).map { it.toByte() }.toByteArray())
        write(flatTop.resolve("notes.txt"), "top level of B\n")
        write(nested.resolve("Foo.class"), "magic-Foo\n")
        write(nested.resolve("Foo\$Bar.class"), "magic-Foo\$Bar\n")
        write(nested.resolve("Foo\$Bar\$Baz.class"), "magic-Foo\$Bar\$Baz\n")

        val classFiles = ClassFileExpander.expand(listOf(nested.resolve("Foo.class")), includeInnerClasses = true)
        val requested = listOf(
            flat.resolve("readme.txt"),
            flat.resolve("data.bin"),
            flatTop.resolve("notes.txt"),
        ) + classFiles

        requested.forEach { local ->
            service.uploadViaRsync(profile, password, listOf(UploadRequest(local, remotePath(local))))
        }

        requested.forEach { local ->
            val localHash = md5(local)
            val remoteHash = remoteHash(service, profile, password, local)
            assertEquals("content of $local differs after upload", localHash, remoteHash)
        }

        assertEquals(
            "remote file count",
            requested.size,
            remoteFileCount(service, profile, password),
        )
    }

    @Test
    fun `sftp uploads all preconfigured files identically`() {
        val service = SftpUploadService()
        val password = "pass".toByteArray()
        val profile = ServerProfile(
            name = "sftp",
            host = host,
            port = sshPort,
            username = "root",
            useRsync = false,
        )

        val flat = root.resolve("A")
        val flatTop = root.resolve("B")
        val nested = flatTop.resolve("nested")
        Files.createDirectories(flat)
        Files.createDirectories(nested)

        write(flat.resolve("readme.txt"), "hello\nworld\n")
        write(flat.resolve("data.bin"), (0..255).map { it.toByte() }.toByteArray())
        write(flatTop.resolve("notes.txt"), "top level of B\n")
        write(nested.resolve("Foo.class"), "magic-Foo\n")
        write(nested.resolve("Foo\$Bar.class"), "magic-Foo\$Bar\n")
        write(nested.resolve("Foo\$Bar\$Baz.class"), "magic-Foo\$Bar\$Baz\n")

        val classFiles = ClassFileExpander.expand(listOf(nested.resolve("Foo.class")), includeInnerClasses = true)
        val requested =
            (listOf(flat.resolve("readme.txt"), flat.resolve("data.bin"), flatTop.resolve("notes.txt")) + classFiles)
                .map { local -> UploadRequest(local, remotePath(local)) }

        service.upload(profile, PasswordAuthentication(password), requested)

        requested.forEach { request ->
            val localHash = md5(request.localFile)
            val remoteHash = service.download(profile, PasswordAuthentication(password), request.remoteFile)
                .let { MessageDigest.getInstance("MD5").digest(it) }
                .joinToString("") { "%02x".format(it) }
            assertEquals("content of ${request.localFile} differs after upload", localHash, remoteHash)
        }

        assertEquals(
            "remote file count",
            requested.size,
            remoteFileCount(service, profile, password),
        )
    }

    private fun startContainer() {
        val candidates = (System.getProperty("rsync.image") ?: "alpine_rsync_ssh:1.0,alpine_rsync_ssh:latest")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var lastError: Exception? = null
        candidates.forEach { reference ->
            try {
                launch(GenericContainer(DockerImageName.parse(reference)))
                return
            } catch (error: Exception) {
                lastError = error
            }
        }

        val dockerfile = System.getProperty("rsync.dockerfile")
        if (dockerfile.isNullOrBlank()) {
            throw IllegalStateException("No rsync image available and no rsync.dockerfile provided to build from", lastError)
        }

        launch(GenericContainer(ImageFromDockerfile().withDockerfile(File(dockerfile).toPath())))
    }

    private fun launch(container: GenericContainer<*>) {
        container.withExposedPorts(22).waitingFor(Wait.forListeningPort()).start()
        this.container = container
        host = container.host
        sshPort = container.getMappedPort(22)
    }

    private fun remotePath(local: Path): String =
        "/upload/" + root.relativize(local).toString().replace(File.separatorChar, '/')

    private fun remoteHash(service: SftpUploadService, profile: ServerProfile, password: ByteArray, local: Path): String {
        val path = remotePath(local)
        val result = service.executeCommand(profile, PasswordAuthentication(password), "md5sum " + shellQuote(path))
        assertTrue("md5sum failed for $path: ${result.standardOutput}", result.exitStatus == 0)
        return result.standardOutput.trim().substringBefore(' ')
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun remoteFileCount(service: SftpUploadService, profile: ServerProfile, password: ByteArray): Int {
        val result = service.executeCommand(profile, PasswordAuthentication(password), "find /upload -type f | wc -l")
        assertTrue("find failed: ${result.standardOutput}", result.exitStatus == 0)
        return result.standardOutput.trim().toInt()
    }

    private fun write(file: Path, content: String): Path =
        Files.write(file, content.toByteArray())

    private fun write(file: Path, content: ByteArray): Path =
        Files.write(file, content)

    private fun md5(file: Path): String =
        MessageDigest.getInstance("MD5").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
