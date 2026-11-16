package com.openfolio.core.data.local.export

import java.io.Writer

/**
 * A fast, lightweight, streaming JSON writer with configurable indentation.
 * Writes directly to the underlying [Writer] with minimal memory footprint,
 * ensuring large database exports do not run into OutOfMemory errors.
 */
class JsonStreamWriter(
    private val writer: Writer,
    private val indent: String = "  ",
) : AutoCloseable {

    private enum class Scope {
        EMPTY_OBJECT,
        NONEMPTY_OBJECT,
        EMPTY_ARRAY,
        NONEMPTY_ARRAY,
        DOC,
    }

    private val stack = ArrayDeque<Scope>().apply { add(Scope.DOC) }

    private fun newline() {
        if (indent.isNotEmpty()) {
            writer.write("\n")
            for (i in 1 until stack.size) {
                writer.write(indent)
            }
        }
    }

    private fun beforeValue() {
        when (stack.last()) {
            Scope.DOC -> {}
            Scope.EMPTY_ARRAY -> {
                stack.removeLast()
                stack.addLast(Scope.NONEMPTY_ARRAY)
                newline()
            }
            Scope.NONEMPTY_ARRAY -> {
                writer.write(",")
                newline()
            }
            Scope.NONEMPTY_OBJECT -> {
                writer.write(": ")
            }
            Scope.EMPTY_OBJECT -> {
                throw IllegalStateException("Expected field name before writing value in JSON object.")
            }
        }
    }

    fun beginObject(): JsonStreamWriter {
        beforeValue()
        writer.write("{")
        stack.addLast(Scope.EMPTY_OBJECT)
        return this
    }

    fun endObject(): JsonStreamWriter {
        val last = stack.removeLast()
        if (last == Scope.NONEMPTY_OBJECT) {
            newline()
        }
        writer.write("}")
        return this
    }

    fun beginArray(): JsonStreamWriter {
        beforeValue()
        writer.write("[")
        stack.addLast(Scope.EMPTY_ARRAY)
        return this
    }

    fun endArray(): JsonStreamWriter {
        val last = stack.removeLast()
        if (last == Scope.NONEMPTY_ARRAY) {
            newline()
        }
        writer.write("]")
        return this
    }

    fun name(name: String): JsonStreamWriter {
        val current = stack.last()
        if (current == Scope.NONEMPTY_OBJECT) {
            writer.write(",")
        } else if (current != Scope.EMPTY_OBJECT) {
            throw IllegalStateException("Field names can only be added to JSON objects, current scope: $current")
        }
        if (current == Scope.EMPTY_OBJECT) {
            stack.removeLast()
            stack.addLast(Scope.NONEMPTY_OBJECT)
        }
        newline()
        writeEscapedString(name)
        return this
    }

    fun value(value: String?): JsonStreamWriter {
        if (value == null) return nullValue()
        beforeValue()
        writeEscapedString(value)
        return this
    }

    fun value(value: Boolean): JsonStreamWriter {
        beforeValue()
        writer.write(if (value) "true" else "false")
        return this
    }

    fun value(value: Long): JsonStreamWriter {
        beforeValue()
        writer.write(value.toString())
        return this
    }

    fun value(value: Double): JsonStreamWriter {
        beforeValue()
        if (value.isNaN() || value.isInfinite()) {
            writer.write("null")
        } else {
            writer.write(value.toString())
        }
        return this
    }

    fun value(value: Number?): JsonStreamWriter {
        if (value == null) return nullValue()
        beforeValue()
        writer.write(value.toString())
        return this
    }

    fun nullValue(): JsonStreamWriter {
        beforeValue()
        writer.write("null")
        return this
    }

    private fun writeEscapedString(s: String) {
        writer.write("\"")
        for (char in s) {
            when (char) {
                '"' -> writer.write("\\\"")
                '\\' -> writer.write("\\\\")
                '\b' -> writer.write("\\b")
                '\u000C' -> writer.write("\\f")
                '\n' -> writer.write("\\n")
                '\r' -> writer.write("\\r")
                '\t' -> writer.write("\\t")
                else -> {
                    if (char.code < 0x20) {
                        writer.write(String.format("\\u%04x", char.code))
                    } else {
                        writer.append(char)
                    }
                }
            }
        }
        writer.write("\"")
    }

    fun flush() {
        writer.flush()
    }

    override fun close() {
        writer.close()
    }
}
