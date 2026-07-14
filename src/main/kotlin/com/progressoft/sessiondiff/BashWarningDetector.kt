package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import java.io.File

object BashWarningDetector {

    private val DESTRUCTIVE_BASH_RE = Regex("""(?:^|[;&|]\s*)(rm|rmdir|mv|git\s+mv)\s""")

    fun bashWarningsFor(transcriptPath: java.nio.file.Path): List<String> {
        val warnings = mutableListOf<String>()
        File(transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: Exception) {
                return@forEachLine
            }
            val message = obj.getAsJsonObject("message") ?: return@forEachLine
            val content = message.getAsJsonArray("content") ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type")?.asString != "tool_use") continue
                if (blockObj.get("name")?.asString != "Bash") continue
                val command = blockObj.getAsJsonObject("input")?.get("command")?.asString ?: continue
                if (DESTRUCTIVE_BASH_RE.containsMatchIn(command)) warnings.add(command)
            }
        }
        return warnings
    }
}
