package org.kavo.uploader.upload

import org.kavo.uploader.formatActionExecutionNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CommandExecutionTest {
    @Test
    fun `formats elapsed time as minutes and seconds`() {
        assertEquals("00:00", formatElapsedTime(0))
        assertEquals("00:01", formatElapsedTime(1_999))
        assertEquals("01:01", formatElapsedTime(61_000))
    }

    @Test
    fun `only zero exit status is successful`() {
        assertTrue(isSuccessfulCommandExit(0))
        assertFalse(isSuccessfulCommandExit(1))
        assertFalse(isSuccessfulCommandExit(-1))
    }

    @Test
    fun `bounded standard output retains empty multiline and UTF-8 output`() {
        val output = BoundedStandardOutput(100)
        assertEquals("", output.decoded())
        assertFalse(output.isTruncated)

        val standardOutput = "first line\nsecond line\nПривет"
        val bytes = standardOutput.toByteArray(StandardCharsets.UTF_8)
        output.append(bytes, 0, bytes.size)

        assertEquals(standardOutput, output.decoded())
        assertFalse(output.isTruncated)
    }

    @Test
    fun `bounded standard output retains cap and marks discarded bytes`() {
        val outputAtCapacity = BoundedStandardOutput(3)
        val output = BoundedStandardOutput(5)
        val firstChunk = "abc".toByteArray(StandardCharsets.UTF_8)
        val secondChunk = "defgh".toByteArray(StandardCharsets.UTF_8)

        outputAtCapacity.append(firstChunk, 0, firstChunk.size)
        output.append(firstChunk, 0, firstChunk.size)
        output.append(secondChunk, 0, secondChunk.size)

        assertEquals("abc", outputAtCapacity.decoded())
        assertFalse(outputAtCapacity.isTruncated)
        assertEquals("abcde", output.decoded())
        assertTrue(output.isTruncated)
    }

    @Test
    fun `action notification renders escaped multiline output before completion`() {
        val notification = formatActionExecutionNotification(
            exitStatus = 0,
            status = "Action deploy completed on production",
            standardOutput = "first <line>\nsecond & \"line\"",
            isOutputTruncated = false,
            truncationMarker = "Output truncated",
            completion = "Completed in 00:01",
        )

        assertTrue(notification.isInformational)
        assertEquals(
            "Action deploy completed on production<br>first &lt;line&gt;<br>second &amp; &quot;line&quot;<br>Completed in 00:01",
            notification.content,
        )
    }

    @Test
    fun `action notification omits blank output marks truncation and errors for nonzero exits`() {
        val notification = formatActionExecutionNotification(
            exitStatus = -1,
            status = "Action deploy on production exited with status -1",
            standardOutput = " \n",
            isOutputTruncated = true,
            truncationMarker = "Output truncated",
            completion = "Completed in 01:01",
        )

        assertFalse(notification.isInformational)
        assertEquals(
            "Action deploy on production exited with status -1<br>Output truncated<br>Completed in 01:01",
            notification.content,
        )
    }

    @Test
    fun `successful action notification with blank output ends with completion`() {
        val notification = formatActionExecutionNotification(
            exitStatus = 0,
            status = "Action deploy completed on production",
            standardOutput = "\n\t",
            isOutputTruncated = false,
            truncationMarker = "Output truncated",
            completion = "Completed in 00:00",
        )

        assertTrue(notification.isInformational)
        assertEquals("Action deploy completed on production<br>Completed in 00:00", notification.content)
    }
}