package org.kavo.uploader.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantChangesTest {
    @Test
    fun `finds static final constants`() {
        val source = """
            public class Config {
                static final int MAX = 10;
                private static final String NAME = "app";
                static final boolean FLAG = true;
            }
            """
        assertEquals(
            mapOf("MAX" to "10", "NAME" to "\"app\"", "FLAG" to "true"),
            initializers(source),
        )
    }

    @Test
    fun `finds interface fields that are constants implicitly`() {
        val source = """
            public interface Config {
                int MAX = 10;
                String NAME = "app";
            }
            """
        assertEquals(listOf("MAX", "NAME"), constantDeclarations(source).keys.toList())
    }

    @Test
    fun `finds a constant of a nested interface and reports its changed value`() {
        val earlier = """
            public interface CBMConstants {
                interface CollectionManagement {
                    String QUEUE_NAME = "CollectionRequests";
                    String MESSAGE_ATTR_SUSPEND_REASON_ID = "SuspendReasonId";
                    BigInteger CM_DATAFLOW_ID = BigInteger.valueOf(9164114322113008940L);
                }
            }
            """
        val current = earlier.replace("SuspendReasonId\"", "SuspendReasonIds\"")
        val change = constantFileChange(earlier, current)
        assertEquals(listOf("MESSAGE_ATTR_SUSPEND_REASON_ID"), change.constantNames)
        assertEquals(
            "MESSAGE_ATTR_SUSPEND_REASON_ID: \"SuspendReasonId\" -> \"SuspendReasonIds\"",
            change.summary,
        )
    }

    @Test
    fun `skips fields whose value is computed at runtime`() {
        val source = """
            public class Config {
                static final int COUNT = list.size();
                static final String NAME = fetch();
                static final List<String> ITEMS = new ArrayList<>();
            }
            """
        assertTrue(constantDeclarations(source).isEmpty())
    }

    @Test
    fun `skips types that cannot be inlined`() {
        val source = """
            public class Config {
                static final Integer COUNT = 1;
                static final java.lang.Long LIMIT = 1;
            }
            """
        assertTrue(constantDeclarations(source).isEmpty())
    }

    @Test
    fun `reads every constant of one statement`() {
        assertEquals(
            mapOf("A" to "1", "B" to "2"),
            initializers("public class C { static final int A = 1, B = 2; }"),
        )
    }

    @Test
    fun `reads the constants that follow one another on one line`() {
        assertEquals(
            listOf("A", "MAX"),
            constantDeclarations("public class C { static final int A = 1; static final int MAX = 9; }")
                .keys
                .toList(),
        )
    }

    @Test
    fun `keeps a comment marker that is inside a string literal`() {
        assertEquals(
            mapOf("URL" to "\"http://host/path\""),
            initializers("""
                public class C {
                    static final String URL = "http://host/path";
                }
                """),
        )
    }

    @Test
    fun `ignores commented out declarations`() {
        val source = """
            public class C {
                // static final int MAX = 10;
                /* static final int MIN = 1; */
            }
            """
        assertTrue(constantDeclarations(source).isEmpty())
    }

    @Test
    fun `a trailing comment does not hide the next declaration`() {
        val source = """
            public class C {
                static final int A = 1; // MAX = 5;
                static final int MAX = 9;
            }
            """
        assertEquals(listOf("A", "MAX"), constantDeclarations(source).keys.toList())
    }

    @Test
    fun `a value change is reported`() {
        val change = constantFileChange(
            oldText = "public class C { static final int MAX = 10; }",
            newText = "public class C { static final int MAX = 20; }",
        )
        assertEquals(listOf("MAX"), change.constantNames)
        assertEquals("MAX: 10 -> 20", change.summary)
    }

    @Test
    fun `a reformatting of the same value is not a change`() {
        val change = constantFileChange(
            oldText = "public class C { static final int SIZE = 2 * 60; }",
            newText = "public class C {\n    static final int SIZE = 2\n            * 60;\n}\n",
        )
        assertTrue(change.constantNames.isEmpty())
    }

    @Test
    fun `whitespace inside a literal belongs to the value`() {
        val change = constantFileChange(
            oldText = "public class C { static final String NAME = \"my app\"; }",
            newText = "public class C { static final String NAME = \"my  app\"; }",
        )
        assertEquals(listOf("NAME"), change.constantNames)
    }

    @Test
    fun `added and removed constants need no other class`() {
        val change = constantFileChange(
            oldText = "public class C { static final int OLD = 1; }",
            newText = "public class C { static final int NEW = 2; }",
        )
        assertTrue(change.constantNames.isEmpty())
    }

    @Test
    fun `a file without an earlier version reports nothing`() {
        val change = constantFileChange(oldText = null, newText = "public class C { static final int MAX = 20; }")
        assertTrue(change.isEmpty())
    }

    @Test
    fun `a constant built from a changed one counts too`() {
        val change = constantFileChange(
            oldText = """
                public class C {
                    static final int MAX = 10;
                    static final int DOUBLE = MAX * 2;
                }
                """,
            newText = """
                public class C {
                    static final int MAX = 20;
                    static final int DOUBLE = MAX * 2;
                }
                """,
        )
        assertEquals(listOf("MAX", "DOUBLE"), change.constantNames)
        assertEquals("MAX: 10 -> 20", change.summary)
    }

    @Test
    fun `several changed constants are listed once each`() {
        val change = constantFileChange(
            oldText = """
                public class C {
                    static final int A = 1;
                    static final String B = "x";
                }
                """,
            newText = """
                public class C {
                    static final int A = 2;
                    static final String B = "y";
                }
                """,
        )
        assertEquals(listOf("A", "B"), change.constantNames)
        assertEquals("A: 1 -> 2, B: \"x\" -> \"y\"", change.summary)
    }

    @Test
    fun `an unchanged file reports nothing`() {
        val source = """
            public class C {
                static final int MAX = 10;
                static final int DOUBLE = MAX * 2;
            }
            """
        assertTrue(constantFileChange(oldText = source, newText = source).isEmpty())
    }

    private fun initializers(source: String): Map<String, String> =
        constantDeclarations(source).map { entry -> entry.key to entry.value.initializer }.toMap()
}
