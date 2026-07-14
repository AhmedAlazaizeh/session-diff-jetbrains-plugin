# session-diff JetBrains Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A JetBrains Platform plugin with a tool window listing Claude Code sessions for the current project; clicking a session opens a native multi-file diff of only what Claude changed that session.

**Architecture:** Kotlin plugin, no Python dependency. Reads the same on-disk data `session-diff.py` already reads (Claude Code transcript JSONL + the Round-7 hook snapshot store at `~/.claude/session-diff-history/`) directly. Builds `DiffContent` from in-memory bytes and opens it via IntelliJ's native `DiffManager` — no shadow-tree directories on disk.

**Tech Stack:** Kotlin, Gradle (`org.jetbrains.intellij.platform` Gradle plugin 2.x), IntelliJ Platform 2026.1, Gson (bundled with the platform, no new dependency).

Deliberate scope decision from the design spec (`docs/superpowers/specs/2026-07-14-jetbrains-plugin-design.md`): no formal unit-test framework for this first pass. Verification is Gradle build success per task, plus a final manual checklist run via `./gradlew runIde`.

---

### Task 1: Gradle project scaffold

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write `settings.gradle.kts`**

Verified against the official `JetBrains/intellij-platform-plugin-template` repo — the `import` line and the `foojay-resolver-convention` plugin are both required, not optional boilerplate; without the import, `intellijPlatform{}` below is an unresolved reference.

```kotlin
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "session-diff"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
```

- [ ] **Step 2: Write `build.gradle.kts`**

No `repositories {}` block here — `FAIL_ON_PROJECT_REPOS` above means repositories are only ever declared in `settings.gradle.kts`; declaring any in `build.gradle.kts` fails the build. The `org.jetbrains.intellij.platform` plugin needs no explicit version — it's resolved via the settings plugin applied above. Use `intellijIdea(...)`, not `intellijIdeaCommunity(...)` — the latter was removed by the platform since 2025.3 and fails dependency resolution. No `bundledPlugin("com.intellij.diff")` either — diff APIs (`DiffManager`, `DiffContentFactory`, used starting Task 5) are part of the base platform, not a separate bundled plugin; declaring one with that ID fails with "Could not find bundled plugin." Kotlin Gradle plugin must be `2.3.0`, not `2.1.20` — IDEA 2026.1's bundled platform jars carry Kotlin metadata compiled with 2.3.0, and the 2.1.20 compiler can't read it ("compiled with an incompatible version of Kotlin"), so any real compilation against the platform jars fails (this didn't surface in Task 1 because zero Kotlin sources existed yet — `NO-SOURCE` skips compilation entirely).

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
    }
}

kotlin {
    jvmToolchain(21)
}
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2g
kotlin.code.style=official
pluginGroup=com.progressoft.sessiondiff
pluginVersion=0.1.0
```

- [ ] **Step 4: Write `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
  <id>com.progressoft.sessiondiff</id>
  <name>Claude Sessions</name>
  <vendor>Ahmad Alazaizeh</vendor>
  <description>Shows a diff of only what Claude Code changed in a session, in the native IDE diff viewer.</description>
  <idea-version since-build="261"/>

  <depends>com.intellij.modules.platform</depends>

  <extensions defaultExtensionNs="com.intellij">
    <toolWindow id="Claude Sessions"
                secondary="true"
                anchor="left"
                factoryClass="com.progressoft.sessiondiff.SessionListToolWindowFactory"/>
  </extensions>
</idea-plugin>
```

- [ ] **Step 5: Bootstrap the Gradle wrapper**

A fresh repo has no `./gradlew` yet — it must be generated once using a system-installed Gradle. If `gradle` isn't on PATH, install it via SDKMAN (`sdk install gradle 9.6.1`, source `~/.sdkman/bin/sdkman-init.sh` first if needed).

```bash
gradle wrapper --gradle-version 9.6.1
```
Expected: `BUILD SUCCESSFUL`; creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`. These get committed to the repo like any other project file — that's the standard, expected Gradle wrapper convention.

- [ ] **Step 6: Verify Gradle itself is configured correctly**

Run: `./gradlew help`
Expected: `BUILD SUCCESSFUL`. Then run `./gradlew tasks | grep -E "buildPlugin|runIde"` — expected output lists both `buildPlugin` and `runIde`, confirming the IntelliJ Platform Gradle plugin applied correctly. (`buildPlugin` isn't run yet — `plugin.xml` references `SessionListToolWindowFactory`, which doesn't exist until Task 3, so a full build would fail until then.)

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties src/main/resources/META-INF/plugin.xml gradlew gradlew.bat gradle/
git commit -m "chore: scaffold Gradle IntelliJ Platform plugin project"
```

---

### Task 2: Session discovery — parse transcripts, list sessions for current project

**Files:**
- Create: `src/main/kotlin/com/progressoft/sessiondiff/SessionInfo.kt`
- Create: `src/main/kotlin/com/progressoft/sessiondiff/SessionDiscoveryService.kt`

- [ ] **Step 1: Write `SessionInfo.kt`**

```kotlin
package com.progressoft.sessiondiff

import java.nio.file.Path

data class SessionInfo(
    val sessionId: String,
    val transcriptPath: Path,
    val startTimeMillis: Long,
    val touchedFileCount: Int,
)
```

- [ ] **Step 2: Write `SessionDiscoveryService.kt`**

```kotlin
package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.exists

object SessionDiscoveryService {

    private val TOOL_NAMES_WITH_FILE = setOf("Edit", "Write", "NotebookEdit")

    fun projectsDir(projectBasePath: String): Path {
        val encoded = projectBasePath.replace("/", "-")
        return Path(System.getProperty("user.home"), ".claude", "projects", encoded)
    }

    fun listSessions(projectBasePath: String): List<SessionInfo> {
        val dir = projectsDir(projectBasePath)
        if (!dir.exists()) return emptyList()

        return dir.listDirectoryEntries("*.jsonl")
            .mapNotNull { parseTranscript(it) }
            .sortedByDescending { it.startTimeMillis }
    }

    private fun parseTranscript(path: Path): SessionInfo? {
        var sessionId: String? = null
        var minTimestampMillis: Long? = null
        val touchedFiles = mutableSetOf<String>()

        File(path.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: JsonSyntaxException) {
                return@forEachLine
            } catch (e: IllegalStateException) {
                return@forEachLine
            }

            if (sessionId == null && obj.has("sessionId")) {
                sessionId = obj.get("sessionId").asString
            }

            if (obj.has("timestamp")) {
                val millis = try {
                    Instant.parse(obj.get("timestamp").asString).toEpochMilli()
                } catch (e: Exception) {
                    null
                }
                if (millis != null && (minTimestampMillis == null || millis < minTimestampMillis!!)) {
                    minTimestampMillis = millis
                }
            }

            val message = obj.getAsJsonObject("message") ?: return@forEachLine
            val content = message.getAsJsonArray("content") ?: return@forEachLine
            for (block in content) {
                if (!block.isJsonObject) continue
                val blockObj = block.asJsonObject
                if (blockObj.get("type")?.asString != "tool_use") continue
                val name = blockObj.get("name")?.asString ?: continue
                if (name !in TOOL_NAMES_WITH_FILE) continue
                val input = blockObj.getAsJsonObject("input") ?: continue
                val filePath = input.get("file_path")?.asString ?: input.get("notebook_path")?.asString
                if (filePath != null) touchedFiles.add(filePath)
            }
        }

        val id = sessionId ?: path.fileName.toString().removeSuffix(".jsonl")
        val startTime = minTimestampMillis ?: return null
        if (touchedFiles.isEmpty()) return null

        return SessionInfo(
            sessionId = id,
            transcriptPath = path,
            startTimeMillis = startTime,
            touchedFileCount = touchedFiles.size,
        )
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/progressoft/sessiondiff/SessionInfo.kt src/main/kotlin/com/progressoft/sessiondiff/SessionDiscoveryService.kt
git commit -m "feat: parse Claude Code transcripts into session list"
```

---

### Task 3: Tool window — list sessions, live-refresh on transcript changes

**Files:**
- Create: `src/main/kotlin/com/progressoft/sessiondiff/SessionListToolWindowFactory.kt`
- Create: `src/main/kotlin/com/progressoft/sessiondiff/SessionListPanel.kt`

- [ ] **Step 1: Write `SessionListPanel.kt`**

```kotlin
package com.progressoft.sessiondiff

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class SessionListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<SessionInfo>()
    private val list = JBList(listModel)
    private val timeFormat = SimpleDateFormat("HH:mm")

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = javax.swing.ListCellRenderer<SessionInfo> { _, value, _, _, _ ->
            javax.swing.JLabel("${timeFormat.format(Date(value.startTimeMillis))} · ${value.touchedFileCount} files")
        }
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                list.selectedValue?.let { DiffPresenter.showDiffForSession(project, it) }
            }
        }
        add(JBScrollPane(list), BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        val basePath = project.basePath ?: return
        val sessions = SessionDiscoveryService.listSessions(basePath)
        listModel.clear()
        sessions.forEach { listModel.addElement(it) }
    }
}
```

- [ ] **Step 2: Write `SessionListToolWindowFactory.kt`**

```kotlin
package com.progressoft.sessiondiff

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SessionListToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionListPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        val basePath = project.basePath
        if (basePath != null) {
            val watchedDir = SessionDiscoveryService.projectsDir(basePath).toString()
            VirtualFileManager.getInstance().addAsyncFileListener(
                AsyncFileListener { events ->
                    val relevant = events.any { it.path.startsWith(watchedDir) }
                    if (!relevant) return@AsyncFileListener null
                    object : AsyncFileListener.ChangeApplier {
                        override fun afterVfsChange() {
                            panel.refresh()
                        }
                    }
                },
                toolWindow.disposable,
            )
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/progressoft/sessiondiff/SessionListToolWindowFactory.kt src/main/kotlin/com/progressoft/sessiondiff/SessionListPanel.kt
git commit -m "feat: add Claude Sessions tool window with live refresh"
```

---

### Task 4: Baseline resolver — own store, checkpoint fallback, untracked-file warning

**Files:**
- Create: `src/main/kotlin/com/progressoft/sessiondiff/BaselineResolver.kt`

- [ ] **Step 1: Write `BaselineResolver.kt`**

```kotlin
package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

sealed class Baseline {
    data class Found(val bytes: ByteArray) : Baseline()
    data class UntrackedNoBaseline(val currentBytes: ByteArray) : Baseline()
    object Missing : Baseline()
}

object BaselineResolver {

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun ownStorePath(sessionId: String, absolutePath: String): Path {
        val key = sha256(absolutePath)
        return Path(System.getProperty("user.home"), ".claude", "session-diff-history", sessionId, key)
    }

    /** Claude Code's own checkpoint fallback: minimum-version trackedFileBackups entry for this path. */
    private fun checkpointBaseline(transcriptPath: Path, sessionId: String, absolutePath: String, projectBasePath: String): ByteArray? {
        val relpath = try {
            Path(projectBasePath).relativize(Path(absolutePath)).toString()
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (relpath.startsWith("..")) return null

        var bestVersion: Int? = null
        var bestBackupFileName: String? = null

        File(transcriptPath.toString()).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = try {
                JsonParser.parseString(line).asJsonObject
            } catch (e: Exception) {
                return@forEachLine
            }
            if (obj.get("type")?.asString != "file-history-snapshot") return@forEachLine
            val tfb = obj.getAsJsonObject("snapshot")?.getAsJsonObject("trackedFileBackups") ?: return@forEachLine
            val entry = tfb.getAsJsonObject(relpath) ?: return@forEachLine
            val version = entry.get("version")?.asInt ?: return@forEachLine
            if (bestVersion == null || version < bestVersion!!) {
                bestVersion = version
                bestBackupFileName = entry.get("backupFileName")?.asString
            }
        }

        val backupFileName = bestBackupFileName ?: return null
        val historyFile = Path(System.getProperty("user.home"), ".claude", "file-history", sessionId, backupFileName)
        if (!historyFile.exists()) return null
        return historyFile.readBytes()
    }

    private fun isGitUntracked(absolutePath: String, projectBasePath: String): Boolean {
        return try {
            val process = ProcessBuilder("git", "-C", projectBasePath, "status", "--porcelain=v1", "--", absolutePath)
                .redirectErrorStream(false)
                .start()
            val output = ByteArrayOutputStream()
            process.inputStream.copyTo(output)
            process.waitFor()
            output.toString().lines().any { it.startsWith("??") }
        } catch (e: Exception) {
            false
        }
    }

    fun resolve(session: SessionInfo, absolutePath: String, projectBasePath: String): Baseline {
        val ownPath = ownStorePath(session.sessionId, absolutePath)
        if (ownPath.exists()) {
            return Baseline.Found(ownPath.readBytes())
        }

        val checkpointBytes = checkpointBaseline(session.transcriptPath, session.sessionId, absolutePath, projectBasePath)
        if (checkpointBytes != null) {
            return Baseline.Found(checkpointBytes)
        }

        val currentFile = Path(absolutePath)
        val currentBytes = if (currentFile.exists()) currentFile.readBytes() else ByteArray(0)
        return if (isGitUntracked(absolutePath, projectBasePath)) {
            Baseline.UntrackedNoBaseline(currentBytes)
        } else {
            Baseline.Missing
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/progressoft/sessiondiff/BaselineResolver.kt
git commit -m "feat: resolve pre-edit baselines with git-untracked fallback"
```

---

### Task 5: Diff presenter — build native multi-file diff from resolved baselines

**Files:**
- Create: `src/main/kotlin/com/progressoft/sessiondiff/BashWarningDetector.kt`
- Create: `src/main/kotlin/com/progressoft/sessiondiff/DiffPresenter.kt`

- [ ] **Step 1: Write `BashWarningDetector.kt`**

Kotlin port of `session-diff.py`'s `DESTRUCTIVE_BASH_RE` — Bash-driven `rm`/`mv`/`git mv` isn't checkpointed by anything (not our own hook, not Claude Code's own checkpointing), so these can only ever be flagged, never diffed.

```kotlin
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
```

- [ ] **Step 2: Write `DiffPresenter.kt`**

```kotlin
package com.progressoft.sessiondiff

import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

object DiffPresenter {

    fun showDiffForSession(project: Project, session: SessionInfo) {
        val projectBasePath = project.basePath ?: return
        val touchedFiles = touchedFilesFor(session)
        if (touchedFiles.isEmpty()) return

        val untrackedWarnings = mutableListOf<String>()
        val requests = mutableListOf<SimpleDiffRequest>()

        for (absolutePath in touchedFiles.sorted()) {
            val relpath = try {
                Path(projectBasePath).relativize(Path(absolutePath)).toString()
            } catch (e: IllegalArgumentException) {
                continue // outside project root — skip, matches session-diff.py's outside_cwd handling
            }
            if (relpath.startsWith("..")) continue

            val baseline = BaselineResolver.resolve(session, absolutePath, projectBasePath)
            val beforeBytes = when (baseline) {
                is Baseline.Found -> baseline.bytes
                is Baseline.UntrackedNoBaseline -> {
                    untrackedWarnings.add(relpath)
                    ByteArray(0)
                }
                Baseline.Missing -> ByteArray(0)
            }

            val currentFile = File(absolutePath)
            val afterBytes = if (currentFile.exists()) currentFile.readBytes() else ByteArray(0)

            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(currentFile.name)
            val diffContentFactory = DiffContentFactory.getInstance()
            val beforeContent = diffContentFactory.create(project, String(beforeBytes), fileType)
            val afterContent = diffContentFactory.create(project, String(afterBytes), fileType)

            requests.add(SimpleDiffRequest(relpath, beforeContent, afterContent, "Before", "After"))
        }

        if (untrackedWarnings.isNotEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Sessions")
                .createNotification(
                    "No baseline — untracked when touched",
                    "Whole-file diff shown, not just Claude's part: ${untrackedWarnings.joinToString(", ")}",
                    NotificationType.WARNING,
                )
                .notify(project)
        }

        val bashWarnings = BashWarningDetector.bashWarningsFor(session.transcriptPath)
        if (bashWarnings.isNotEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Sessions")
                .createNotification(
                    "Not checkpointed — verify manually",
                    "Bash commands that may have deleted/moved files:\n${bashWarnings.joinToString("\n")}",
                    NotificationType.WARNING,
                )
                .notify(project)
        }

        if (requests.isEmpty()) return
        val chain = SimpleDiffRequestChain(requests)
        DiffManager.getInstance().showDiff(project, chain, com.intellij.diff.DiffDialogHints.DEFAULT)
    }

    private fun touchedFilesFor(session: SessionInfo): Set<String> {
        val touched = mutableSetOf<String>()
        File(session.transcriptPath.toString()).forEachLine { line ->
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
                val name = blockObj.get("name")?.asString ?: continue
                if (name !in setOf("Edit", "Write", "NotebookEdit")) continue
                val input = blockObj.getAsJsonObject("input") ?: continue
                val filePath = input.get("file_path")?.asString ?: input.get("notebook_path")?.asString
                if (filePath != null) touched.add(filePath)
            }
        }
        return touched
    }
}
```

- [ ] **Step 3: Register the notification group used above**

Add to `src/main/resources/META-INF/plugin.xml`, inside the existing `<extensions defaultExtensionNs="com.intellij">` block (alongside the `toolWindow` entry already there):

```xml
    <notificationGroup id="Claude Sessions" displayType="BALLOON"/>
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/progressoft/sessiondiff/DiffPresenter.kt src/main/kotlin/com/progressoft/sessiondiff/BashWarningDetector.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: open native multi-file diff on session click, flag Bash deletes/moves"
```

---

### Task 6: Manual verification against real regression cases

**Files:** none created — this task runs the assembled plugin and checks behavior.

- [ ] **Step 1: Launch a sandboxed IDE with the plugin loaded**

Run: `./gradlew runIde`
Expected: a new IntelliJ IDEA instance opens with the plugin installed. Wait for it to finish starting.

- [ ] **Step 2: Untracked file with a Round-7 hook snapshot → precise line-level diff**

In the sandbox IDE, open the PayHub backend project (or any project with `fileCheckpointingEnabled: true` and the Round-7 `PreToolUse` hook registered). Create a new untracked file, edit it with Claude Code in a real session, open the "Claude Sessions" tool window, click that session.
Expected: diff shows only the added line(s), not the whole file.

- [ ] **Step 3: Staged-but-uncommitted file → precise diff, no warning**

`git add` a new file without committing, edit it via Claude, click the session in the tool window.
Expected: precise line-level diff, no untracked-baseline notification balloon.

- [ ] **Step 4: File touched outside the project root → excluded, not crashed**

Have Claude edit a file outside the project directory (e.g. under `/tmp`) in the same session as an in-project file.
Expected: tool window session click succeeds without error; only the in-project file appears in the diff chain; the out-of-project file is silently excluded (matches `session-diff.py`'s `outside_cwd` behavior — no notification needed for this case per the design spec, since it's not the user's own untracked work, just genuinely irrelevant to this project).

- [ ] **Step 5: Fully untracked file with no snapshot at all (pre-hook session) → warning shown**

Temporarily disable the Round-7 hook (comment out its `PreToolUse` registration in `~/.claude/settings.json`), have Claude edit a genuinely untracked file, re-enable the hook, then view that session's diff.
Expected: a "No baseline — untracked when touched" warning balloon appears; the diff shows the whole file.

- [ ] **Step 6: Session with a Bash `rm`/`mv` → flagged, not silently included**

Have Claude run `rm somefile.txt` or `mv a.txt b.txt` via Bash in a session (in addition to a normal Edit on some other file), then click that session in the tool window.
Expected: a "Not checkpointed — verify manually" warning balloon lists the exact Bash command; the diff chain still opens normally for the Edit-touched file.

- [ ] **Step 7: Session list live-refreshes**

With the sandbox IDE still open and the tool window visible, start a brand new Claude Code session in that project and make one edit.
Expected: the new session appears in the tool window list without manually reopening the tool window.

- [ ] **Step 8: Commit** (only if any fixes were needed during verification; otherwise skip)

```bash
git add -A
git commit -m "fix: address issues found during manual verification"
```

---

### Task 7: Push to GitLab

**Files:** none created — this task publishes the already-committed local repo.

- [ ] **Step 1: Create the private repo**

```bash
GITLAB_HOST=gitlab.progressoft.io glab repo create session-diff-jetbrains-plugin --private --description "IntelliJ Platform plugin: session-scoped diff viewer for Claude Code"
```
Expected: `✓ Created project on GitLab: ... session-diff-jetbrains-plugin`

- [ ] **Step 2: Add the remote and push**

```bash
git remote add origin git@gitlab.progressoft.io:ps.Ahmad.Alazaizeh/session-diff-jetbrains-plugin.git
git branch -M main
git push -u origin main
```
Expected: push succeeds (watch for the same commit-message-format pre-receive hook seen on the CLI plugin repo — if rejected, amend the offending commit message to match `^(feat|fix|docs|...): ...` and retry)

- [ ] **Step 3: Verify**

```bash
GITLAB_HOST=gitlab.progressoft.io glab repo view ps.Ahmad.Alazaizeh/session-diff-jetbrains-plugin
```
Expected: shows the repo name and description, confirming the push landed correctly.
