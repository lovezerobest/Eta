package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkingTest {
    @Test
    fun smallDocumentRemainsOneChunk() {
        assertEquals(listOf("hello"), MarkdownChunking.split("hello", targetChars = 3))
    }

    @Test
    fun largeDocumentPrefersBlankLineBoundaries() {
        val source = "第一段内容\n\n第二段内容\n\n第三段内容"
        val chunks = MarkdownChunking.split(source, targetChars = 8)
        assertEquals(listOf("第一段内容", "第二段内容", "第三段内容"), chunks)
    }

    @Test
    fun fencedCodeIsNotSplitAtInternalBlankLines() {
        val source = "说明\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n结尾"
        val chunks = MarkdownChunking.split(source, targetChars = 10)
        assertTrue(chunks.any { it.contains("```kotlin") && it.contains("```") && it.contains("val b") })
    }
}
