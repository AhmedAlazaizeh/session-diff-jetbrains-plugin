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
