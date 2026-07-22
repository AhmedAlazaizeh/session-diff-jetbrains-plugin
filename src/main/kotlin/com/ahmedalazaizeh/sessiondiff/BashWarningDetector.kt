package com.ahmedalazaizeh.sessiondiff

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
            val message = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEachLine
            val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type").jsonString() != "tool_use") continue
                if (blockObj.get("name").jsonString() != "Bash") continue
                val command = blockObj.get("input").jsonObject()?.get("command").jsonString() ?: continue
                if (DESTRUCTIVE_BASH_RE.containsMatchIn(command)) warnings.add(command)
            }
        }
        return warnings
    }
}
