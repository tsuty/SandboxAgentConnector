package com.tsuty.sandboxagent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnifiedDiffSupportTest {

    private val diff = """
        diff --git a/Sample.kt b/Sample.kt
        index 1111111..2222222 100644
        --- a/Sample.kt
        +++ b/Sample.kt
        @@ -1,3 +1,4 @@
         fun main() {
        -    println("old")
        +    println("new")
        +    println("extra")
         }
    """.trimIndent()

    @Test
    fun `counts added and removed lines ignoring headers`() {
        val stats = UnifiedDiffSupport.stats(diff)
        assertEquals(2, stats.added)
        assertEquals(1, stats.removed)
        assertEquals("+2 / -1", stats.presentableText)
    }

    @Test
    fun `empty diff has no changes`() {
        assertEquals(DiffStats(0, 0), UnifiedDiffSupport.stats(null))
        assertEquals(DiffStats(0, 0), UnifiedDiffSupport.stats(""))
    }

    @Test
    fun `rebuilds both sides of the patch`() {
        val (before, after) = UnifiedDiffSupport.sides(diff)
        assertEquals("fun main() {\n    println(\"old\")\n}\n", before)
        assertEquals("fun main() {\n    println(\"new\")\n    println(\"extra\")\n}\n", after)
    }

    @Test
    fun `sides of an empty diff are empty`() {
        assertEquals("" to "", UnifiedDiffSupport.sides(null))
    }

    @Test
    fun `builds a unified diff from the acp old and new text`() {
        val simple = UnifiedDiffSupport.fromTexts(
            path = "/workspace/Sample.kt",
            oldText = "fun main() {\n    println(\"old\")\n}\n",
            newText = "fun main() {\n    println(\"new\")\n    println(\"extra\")\n}\n",
        )

        assertEquals(DiffStats(2, 1), simple.stats)
        assertEquals(
            """
                --- /workspace/Sample.kt
                +++ /workspace/Sample.kt
                @@ -1,3 +1,4 @@
                 fun main() {
                -    println("old")
                +    println("new")
                +    println("extra")
                 }
            """.trimIndent() + "\n",
            simple.text,
        )
        assertEquals("/workspace/Sample.kt  +2 / -1", simple.presentableText)
        assertEquals("Sample.kt", simple.fileName)
    }

    @Test
    fun `keeps only the changed part of a long file`() {
        val oldText = (1..20).joinToString("\n") { "line $it" }
        val newText = oldText.replace("line 10", "line ten")

        val simple = UnifiedDiffSupport.fromTexts("/workspace/Long.txt", oldText, newText)

        assertEquals(DiffStats(1, 1), simple.stats)
        assertEquals(
            listOf(
                "--- /workspace/Long.txt",
                "+++ /workspace/Long.txt",
                "@@ -7,7 +7,7 @@",
                " line 7",
                " line 8",
                " line 9",
                "-line 10",
                "+line ten",
                " line 11",
                " line 12",
                " line 13",
            ),
            simple.lines,
        )
    }

    @Test
    fun `reports the two hunks of two distant changes separately`() {
        val oldText = (1..40).joinToString("\n") { "line $it" }
        val newText = oldText.replace("line 2\n", "line two\n").replace("line 30", "line thirty")

        val simple = UnifiedDiffSupport.fromTexts("/workspace/Long.txt", oldText, newText)

        assertEquals(DiffStats(2, 2), simple.stats)
        assertEquals(2, simple.lines.count { it.startsWith("@@") })
    }

    @Test
    fun `moved lines keep their surrounding context`() {
        val simple = UnifiedDiffSupport.fromTexts(
            path = "/workspace/sample.txt",
            oldText = "a\nc\nb\nz\n",
            newText = "a\nb\nc\nz\n",
        )

        assertEquals(DiffStats(1, 1), simple.stats)
        assertEquals(
            listOf(
                "--- /workspace/sample.txt",
                "+++ /workspace/sample.txt",
                "@@ -1,4 +1,4 @@",
                " a",
                "-c",
                " b",
                "+c",
                " z",
            ),
            simple.lines,
        )
    }

    @Test
    fun `a new file counts every line as added`() {
        val simple = UnifiedDiffSupport.fromTexts("/workspace/New.kt", null, "a\nb")
        assertEquals(DiffStats(2, 0), simple.stats)
        assertEquals(
            listOf(
                "--- /workspace/New.kt",
                "+++ /workspace/New.kt",
                "@@ -0,0 +1,2 @@",
                "+a",
                "+b",
            ),
            simple.lines,
        )
    }

    @Test
    fun `an unchanged text has no hunk at all`() {
        val simple = UnifiedDiffSupport.fromTexts("/workspace/Same.kt", "a\nb\n", "a\nb\n")
        assertEquals(DiffStats(0, 0), simple.stats)
        assertEquals(listOf("--- /workspace/Same.kt", "+++ /workspace/Same.kt"), simple.lines)
    }
}
