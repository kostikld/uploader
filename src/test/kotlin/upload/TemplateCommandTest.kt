package org.kavo.uploader.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCommandTest {
    @Test
    fun `extracts unique template parameters in first-use order`() {
        assertEquals(
            listOf("pid", "file"),
            actionTemplateParams("kill ${'$'}{pid} && cp ${'$'}{file} ${'$'}{file}"),
        )
    }

    @Test
    fun `substitutes values and leaves blank parameters empty`() {
        assertEquals(
            "kill 123 && cp  ",
            renderActionCommand(
                "kill ${'$'}{pid} && cp ${'$'}{file} ${'$'}{file}",
                mapOf("pid" to "123", "file" to ""),
            ),
        )
    }

    @Test
    fun `accepts digit-leading placeholders as parameters`() {
        assertEquals(listOf("1", "3"), actionTemplateParams("${'$'}{1}a${'$'}{3}e"))
        assertEquals("xaye", renderActionCommand("${'$'}{1}a${'$'}{3}e", mapOf("1" to "x", "3" to "y")))
    }

    @Test
    fun `ignores malformed and invalid placeholders`() {
        assertEquals(
            listOf("1x", "ok"),
            actionTemplateParams("${'$'} {x} {} ${'$'}{} ${'$'}{1x} ${'$'}{a-b} ${'$'}{ok}"),
        )
    }

    @Test
    fun `renders unmatched placeholders unchanged and missing values empty`() {
        assertEquals(
            "x  ${'$'}{}",
            renderActionCommand("${'$'}{a} ${'$'}{b} ${'$'}{}", mapOf("a" to "x")),
        )
    }

    @Test
    fun `substituted value containing a placeholder is not rescanned`() {
        assertEquals(
            "${'$'}{y}",
            renderActionCommand("${'$'}{x}", mapOf("x" to "${'$'}{y}")),
        )
    }

    @Test
    fun `commands without placeholders are left unchanged`() {
        assertTrue(actionTemplateParams("uptime").isEmpty())
        assertEquals("uptime", renderActionCommand("uptime", mapOf()))
    }
}
