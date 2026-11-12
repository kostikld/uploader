package org.kavo.uploader.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FnMatchTest {
    @Test
    fun `star spans path separators`() {
        assertTrue(FnMatch.matches("target/**", "target/classes/a.class"))
        assertTrue(FnMatch.matches("*.js", "src/main/web/app.js"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        assertTrue(FnMatch.matches("src/?.js", "src/a.js"))
        assertFalse(FnMatch.matches("src/?.js", "src/ab.js"))
    }

    @Test
    fun `character classes match one character`() {
        assertTrue(FnMatch.matches("file[0-9].txt", "file3.txt"))
        assertFalse(FnMatch.matches("file[0-9].txt", "filea.txt"))
        assertTrue(FnMatch.matches("file[!0-9].txt", "filea.txt"))
        assertFalse(FnMatch.matches("file[!0-9].txt", "file3.txt"))
    }

    @Test
    fun `dots and slashes are literal`() {
        assertFalse(FnMatch.matches("a.js", "axjs"))
        assertFalse(FnMatch.matches("a.js", "a/js"))
    }

    @Test
    fun `pattern without wildcards matches the exact path`() {
        assertTrue(FnMatch.matches("docs/readme.md", "docs/readme.md"))
        assertFalse(FnMatch.matches("docs/readme.md", "docs/readme.md.bak"))
    }

    @Test
    fun `matching is case sensitive`() {
        assertFalse(FnMatch.matches("docs/*.js", "Docs/a.js"))
        assertFalse(FnMatch.matches("src/app.js", "src/APP.js"))
    }

    @Test
    fun `literal prefix stops at the first wildcard segment`() {
        assertEquals("target/classes", FnMatch.literalPrefix("target/classes/**/ui/*.js"))
        assertEquals("", FnMatch.literalPrefix("**/*.js"))
        assertEquals("src/web/app.js", FnMatch.literalPrefix("src/web/app.js"))
        assertEquals("src", FnMatch.literalPrefix("src/*/app.js"))
    }

    @Test
    fun `a trailing globstar captures the remainder`() {
        assertEquals(
            "com/example/Service.class",
            FnMatch.trailingGlobstarRemainder("module/*/target/classes/**", "module/app/target/classes/com/example/Service.class"),
        )
        assertEquals("main/web/app.js", FnMatch.trailingGlobstarRemainder("src/**", "src/main/web/app.js"))
        assertEquals("a/b.txt", FnMatch.trailingGlobstarRemainder("**", "a/b.txt"))
    }

    @Test
    fun `a trailing globstar remainder is null without a globstar or a match`() {
        assertNull(FnMatch.trailingGlobstarRemainder("src/web/**.js", "src/web/app.js"))
        assertNull(FnMatch.trailingGlobstarRemainder("src/classes", "src/classes/a.js"))
        assertNull(FnMatch.trailingGlobstarRemainder("src/**", "lib/main.js"))
    }

    @Test
    fun `wildcard count ranks concrete patterns below literal ones`() {
        assertEquals(1, FnMatch.wildcardCount("src/*.js"))
        assertEquals(3, FnMatch.wildcardCount("src/**/ui/*.js"))
        assertEquals(0, FnMatch.wildcardCount("src/web/app.js"))
        assertEquals(1, FnMatch.wildcardCount("file[0-9].txt"))
    }

    @Test
    fun `a local path with a wildcard is read as a pattern`() {
        assertTrue(FnMatch.isPattern("src/web/**"))
        assertTrue(FnMatch.isPattern("docs/?.html"))
        assertTrue(FnMatch.isPattern("src/[draft]"))
        assertFalse(FnMatch.isPattern("src/web/app.js"))
        assertFalse(FnMatch.isPattern(""))
    }
}
