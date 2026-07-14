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
