package org.kavo.uploader.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class GitScannerTest {
    private fun paths(text: String) = GitScanner.parsePorcelain(text).map { it.toString() }

    @Test
    fun `keeps modified files`() {
        assertEquals(listOf("src/Service.java"), paths(" M src/Service.java\n"))
        }

    @Test
    fun `keeps staged additions as new path`() {
        assertEquals(listOf("src/New.java"), paths("A  src/New.java\n"))
        }

    @Test
    fun `keeps renames using the new path`() {
        assertEquals(listOf("src/Renamed.java"), paths("R  src/Old.java -> src/Renamed.java\n"))
        }

      @Test
    fun `ignores untracked files`() {
        assertEquals(emptyList<String>(), paths("?? src/Untracked.java\n"))
          }

     @Test
    fun `ignores deleted files`() {
        assertEquals(emptyList<String>(), paths("D  src/Gone.java\n"))
          }

     @Test
    fun `ignores ignored files`() {
        assertEquals(emptyList<String>(), paths("!! src/Ignored.java\n"))
          }

     @Test
    fun `ignores unmerged files`() {
        assertEquals(emptyList<String>(), paths("UU src/Conflict.java\n"))
          }

     @Test
    fun `handles empty output`() {
        assertEquals(emptyList<String>(), paths(""))
          }

     @Test
    fun `handles a mix of states`() {
        val separator = "\u0000"
        val text =
              " M src/Modified.java$separator" +
                 "A  src/Added.java$separator" +
                 "R  src/A.java -> src/B.java$separator" +
                 "?? src/Untracked.java$separator" +
                 "D  src/Deleted.java$separator" +
                 ""
        assertEquals(
             listOf("src/Modified.java", "src/Added.java", "src/B.java"),
             paths(text),
            )
          }

    @Test
    fun `parses NUL separated records`() {
        val text = "M  src/One.java\u0000M  src/Two.java\u0000"
        assertEquals(listOf("src/One.java", "src/Two.java"), paths(text))
          }

    @Test
    fun `keeps spaces in path names from NUL output`() {
        val line = "A  src/New File.java"
        assertEquals(listOf("src/New File.java"), paths(line + "\u0000"))
          }

    @Test
    fun `uses rename new path even when it has spaces`() {
        val line = "R  src/Old Name.java -> src/New Name.java"
        assertEquals(listOf("src/New Name.java"), paths(line + "\u0000"))
          }
}
