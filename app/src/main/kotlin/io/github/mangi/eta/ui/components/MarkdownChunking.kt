package io.github.mangi.eta.ui.components

/** Splits large Markdown into bounded block-sized render subtrees. */
internal object MarkdownChunking {
    const val LARGE_DOCUMENT_CHARS = 24_000
    const val TARGET_CHUNK_CHARS = 8_000

    fun split(source: String, targetChars: Int = TARGET_CHUNK_CHARS): List<String> {
        if (source.length <= targetChars) return listOf(source)
        val blocks = blockSplit(source)
        val result = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString().trimEnd('\n')
                current.clear()
            }
        }

        for (block in blocks) {
            if (block.length > targetChars && !block.contains("```") && !block.contains("~~~")) {
                flush()
                splitLongPlainBlock(block, targetChars, result)
            } else if (current.isNotEmpty() && current.length + block.length + 2 > targetChars) {
                flush()
                current.append(block)
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(block)
            }
        }
        flush()
        return result.ifEmpty { listOf(source) }
    }

    private fun blockSplit(source: String): List<String> {
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        var fence: Fence? = null

        fun flush() {
            val block = current.toString().trim()
            if (block.isNotEmpty()) blocks += block
            current.clear()
        }

        source.split('\n').forEach { line ->
            current.append(line).append('\n')
            val marker = fenceMarker(line)
            if (marker != null) {
                fence = when {
                    fence == null && !marker.isClosing -> marker
                    fence != null && marker.isClosing && marker.char == fence!!.char && marker.length >= fence!!.length -> null
                    else -> fence
                }
            }
            if (line.isBlank() && fence == null) flush()
        }
        flush()
        return blocks
    }

    private fun splitLongPlainBlock(block: String, targetChars: Int, result: MutableList<String>) {
        val lines = block.split('\n')
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + line.length + 1 > targetChars) {
                result += current.toString().trimEnd('\n')
                current.clear()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) result += current.toString().trimEnd('\n')
    }

    private fun fenceMarker(line: String): Fence? {
        val text = line.dropWhile { it == ' ' }
        val char = text.firstOrNull { it == '`' || it == '~' } ?: return null
        if (text.indexOf(char) != 0) return null
        var length = 0
        while (length < text.length && text[length] == char) length++
        return Fence(char, length, text.substring(length).isBlank()).takeIf { length >= 3 }
    }

    private data class Fence(val char: Char, val length: Int, val isClosing: Boolean)
}
