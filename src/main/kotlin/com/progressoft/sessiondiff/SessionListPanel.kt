package com.progressoft.sessiondiff

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager

private const val CARD_LIST = "list"
private const val CARD_FILES = "files"

private val NEW_FILE_COLOR = JBColor(Color(0, 128, 0), Color(98, 151, 85))
private val ADDED_COUNT_COLOR = NEW_FILE_COLOR
private val DELETED_COUNT_COLOR = JBColor(Color(196, 0, 0), Color(199, 84, 80))

class SessionListPanel(private val project: Project) : JPanel(CardLayout()) {

    private val cardLayout = layout as CardLayout
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    private val sessionListModel = DefaultListModel<SessionInfo>()
    private val sessionList = JBList(sessionListModel)

    private val fileListModel = DefaultListModel<FileChangeSummary>()
    private val fileList = JBList(fileListModel)
    private val filesHeaderTitle = JLabel()
    private val filesHeaderSubtitle = JLabel()
    private var currentSession: SessionInfo? = null

    init {
        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        sessionList.emptyText.text = "No Claude sessions found for this project"
        sessionList.cellRenderer = ListCellRenderer<SessionInfo> { _, value, _, isSelected, _ -> sessionCard(value, isSelected) }
        sessionList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                sessionList.selectedValue?.let { showFilesFor(it) }
            }
        }
        add(JBScrollPane(sessionList), CARD_LIST)

        val backButton = JButton(AllIcons.Actions.Back)
        backButton.toolTipText = "Back to sessions"
        backButton.addActionListener { cardLayout.show(this, CARD_LIST) }

        filesHeaderTitle.font = filesHeaderTitle.font.deriveFont(Font.BOLD)
        filesHeaderSubtitle.foreground = UIManager.getColor("Label.disabledForeground")
        filesHeaderSubtitle.font = filesHeaderSubtitle.font.deriveFont(filesHeaderSubtitle.font.size2D - 1f)
        val headerText = JPanel()
        headerText.layout = BoxLayout(headerText, BoxLayout.Y_AXIS)
        headerText.isOpaque = false
        headerText.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
        headerText.add(filesHeaderTitle)
        headerText.add(filesHeaderSubtitle)

        val header = JPanel(BorderLayout())
        header.add(backButton, BorderLayout.WEST)
        header.add(headerText, BorderLayout.CENTER)
        header.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
        )

        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        fileList.emptyText.text = "No files in this project were touched"
        fileList.cellRenderer = ListCellRenderer<FileChangeSummary> { _, value, _, isSelected, _ -> fileRow(value, isSelected) }
        fileList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val summary = fileList.selectedValue ?: return@addListSelectionListener
                currentSession?.let { DiffPresenter.showDiffForFile(project, it, summary.relpath) }
            }
        }
        val filesPanel = JPanel(BorderLayout())
        filesPanel.add(header, BorderLayout.NORTH)
        filesPanel.add(JBScrollPane(fileList), BorderLayout.CENTER)
        add(filesPanel, CARD_FILES)

        refresh()
    }

    fun refresh() {
        val basePath = project.basePath ?: return
        val sessions = SessionDiscoveryService.listSessions(basePath)
        sessionListModel.clear()
        sessions.forEach { sessionListModel.addElement(it) }
    }

    private fun showFilesFor(session: SessionInfo) {
        currentSession = session
        filesHeaderTitle.text = session.title
        filesHeaderSubtitle.text = "${dateFormat.format(Date(session.startTimeMillis))} · ${session.touchedFileCount} files"
        fileListModel.clear()
        DiffPresenter.fileSummaries(project, session).forEach { fileListModel.addElement(it) }

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

        cardLayout.show(this, CARD_FILES)
    }

    private fun fileRow(summary: FileChangeSummary, isSelected: Boolean): JPanel {
        val foreground = if (isSelected) UIManager.getColor("List.selectionForeground") else UIManager.getColor("List.foreground")
        val secondaryForeground = if (isSelected) foreground else UIManager.getColor("Label.disabledForeground")
        val nameColor = when (summary.category) {
            ChangeCategory.NEW -> if (isSelected) foreground else NEW_FILE_COLOR
            ChangeCategory.DELETED -> secondaryForeground
            ChangeCategory.MODIFIED -> foreground
        }

        val file = File(summary.relpath)
        val icon = FileTypeManager.getInstance().getFileTypeByFileName(file.name).icon
        val iconLabel = JLabel(icon)
        iconLabel.border = BorderFactory.createEmptyBorder(0, 0, 0, 8)

        val nameLabel = JLabel(file.name)
        nameLabel.foreground = nameColor
        nameLabel.alignmentX = 0f

        val textPanel = JPanel()
        textPanel.isOpaque = false
        textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
        textPanel.add(nameLabel)

        val parent = file.parent
        if (parent != null) {
            val dirLabel = JLabel(parent)
            dirLabel.foreground = secondaryForeground
            dirLabel.font = dirLabel.font.deriveFont(dirLabel.font.size2D - 1f)
            dirLabel.alignmentX = 0f
            textPanel.add(dirLabel)
        }

        val addedColor = if (isSelected) foreground else ADDED_COUNT_COLOR
        val deletedColor = if (isSelected) foreground else DELETED_COUNT_COLOR
        val statsComponent = if (summary.isBinary) {
            val binaryLabel = JLabel("binary")
            binaryLabel.foreground = secondaryForeground
            binaryLabel
        } else {
            val addedLabel = JLabel("+${summary.linesAdded}")
            addedLabel.foreground = addedColor
            addedLabel.font = addedLabel.font.deriveFont(addedLabel.font.size2D - 1f)
            addedLabel.alignmentX = 0.5f
            val deletedLabel = JLabel("-${summary.linesDeleted}")
            deletedLabel.foreground = deletedColor
            deletedLabel.font = deletedLabel.font.deriveFont(deletedLabel.font.size2D - 1f)
            deletedLabel.alignmentX = 0.5f

            val panel = JPanel()
            panel.isOpaque = false
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.add(addedLabel)
            panel.add(deletedLabel)
            panel
        }

        val chevron = JLabel(AllIcons.General.ChevronRight)
        chevron.horizontalAlignment = SwingConstants.RIGHT

        val westPanel = JPanel()
        westPanel.isOpaque = false
        westPanel.layout = BoxLayout(westPanel, BoxLayout.X_AXIS)
        westPanel.add(iconLabel)
        westPanel.add(statsComponent)
        westPanel.add(Box.createHorizontalStrut(10))

        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = if (isSelected) UIManager.getColor("List.selectionBackground") else UIManager.getColor("List.background")
        row.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )
        row.add(westPanel, BorderLayout.WEST)
        row.add(textPanel, BorderLayout.CENTER)
        row.add(chevron, BorderLayout.EAST)
        return row
    }

    private fun sessionCard(session: SessionInfo, isSelected: Boolean): JPanel {
        val listBackground = UIManager.getColor("List.background")
        val foreground = if (isSelected) UIManager.getColor("List.selectionForeground") else UIManager.getColor("List.foreground")
        val secondaryForeground = if (isSelected) foreground else UIManager.getColor("Label.disabledForeground")

        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.isOpaque = true
        card.background = if (isSelected) UIManager.getColor("List.selectionBackground") else listBackground
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground"), 1, true),
            BorderFactory.createEmptyBorder(14, 16, 14, 16),
        )

        val titleLabel = JLabel(session.title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, titleLabel.font.size2D + 1f)
        titleLabel.foreground = foreground
        titleLabel.alignmentX = 0f

        val dateLabel = JLabel(dateFormat.format(Date(session.startTimeMillis)))
        dateLabel.foreground = secondaryForeground
        dateLabel.alignmentX = 0f

        val count = session.touchedFileCount
        val filesLabel = JLabel("$count file${if (count == 1) "" else "s"} changed")
        filesLabel.font = filesLabel.font.deriveFont(filesLabel.font.size2D - 1f)
        filesLabel.foreground = secondaryForeground
        filesLabel.alignmentX = 0f

        card.add(titleLabel)
        card.add(Box.createVerticalStrut(6))
        card.add(dateLabel)
        card.add(filesLabel)

        // Margin lives on a separate opaque wrapper (not the card itself) so the gap between
        // cards always reads as plain list background, never the card's selection highlight.
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = listBackground
        wrapper.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        wrapper.add(card, BorderLayout.CENTER)
        return wrapper
    }
}
