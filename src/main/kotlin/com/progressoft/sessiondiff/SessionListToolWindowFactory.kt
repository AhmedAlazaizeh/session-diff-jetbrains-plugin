package com.progressoft.sessiondiff

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import kotlin.io.path.Path

class SessionListToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SessionListPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        val basePath = project.basePath
        if (basePath != null) {
            val watchedDir = SessionDiscoveryService.projectsDir(basePath)
            VirtualFileManager.getInstance().addAsyncFileListener(
                AsyncFileListener { events ->
                    // Path.startsWith is segment-aware, unlike raw String.startsWith — two
                    // projects whose encoded paths happen to share a string prefix (e.g.
                    // "-home-ahmad-foo" and "-home-ahmad-foobar") must not cross-trigger refresh.
                    val relevant = events.any { Path(it.path).startsWith(watchedDir) }
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
