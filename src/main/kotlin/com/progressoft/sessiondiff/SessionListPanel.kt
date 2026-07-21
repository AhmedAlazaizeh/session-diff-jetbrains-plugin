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
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager

private const val CARD_LIST = "list"
private const val CARD_FILES = "files"
private const val MIN_WIDTH_FOR_PATH = 260

private val NEW_FILE_COLOR = JBColor(Color(0, 128, 0), Color(98, 151, 85))
private val ADDED_COUNT_COLOR = NEW_FILE_COLOR
private val DELETED_COUNT_COLOR = JBColor(Color(196, 0, 0), Color(199, 84, 80))
private val KEEP_COLOR = JBColor(Color(46, 160, 67), Color(63, 185, 80))
private val REJECT_COLOR = JBColor(Color(209, 36, 47), Color(224, 90, 90))

class SessionListPanel(private val project: Project) : JPanel(CardLayout()) {

    private val cardLayout = layout as CardLayout
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    private val sessionListModel = DefaultListModel<SessionInfo>()
    private val sessionList = JBList(sessionListModel)

    // Real per-row panels, not a JList — a ListCellRenderer's component is only ever painted, never
    // a live child, so buttons inside it would never actually receive clicks.
    private val filesListContainer = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 16
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 64
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
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

        filesListContainer.layout = BoxLayout(filesListContainer, BoxLayout.Y_AXIS)
        filesListContainer.isOpaque = true
        filesListContainer.background = UIManager.getColor("List.background")

        val filesPanel = JPanel(BorderLayout())
        filesPanel.add(header, BorderLayout.NORTH)
        filesPanel.add(JBScrollPane(filesListContainer), BorderLayout.CENTER)
        add(filesPanel, CARD_FILES)

        refresh()
        // The transcripts directory lives outside any project's content root, so the platform's
        // native file watcher often never notices new/changed files there — poll instead of
        // relying on VFS change events (see SessionListToolWindowFactory's AsyncFileListener).
        Timer(3000) {
            refresh()
            if (currentSession != null) refreshFiles()
        }.apply { isRepeats = true; start() }
    }

    fun refresh() {
        val basePath = project.basePath ?: return
        val sessions = SessionDiscoveryService.listSessions(basePath)
        val current = (0 until sessionListModel.size()).map { sessionListModel.getElementAt(it) }
        if (current == sessions) return
        sessionListModel.clear()
        sessions.forEach { sessionListModel.addElement(it) }
    }

    private fun showFilesFor(session: SessionInfo) {
        currentSession = session
        filesHeaderTitle.text = session.title
        filesHeaderSubtitle.text = "${dateFormat.format(Date(session.startTimeMillis))} · ${session.touchedFileCount} files"
        refreshFiles()

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

    private fun refreshFiles() {
        val session = currentSession ?: return
        filesListContainer.removeAll()
        val summaries = DiffPresenter.fileSummaries(project, session)
        if (summaries.isEmpty()) {
            val empty = JLabel("No files in this project were touched", SwingConstants.CENTER)
            empty.foreground = UIManager.getColor("Label.disabledForeground")
            empty.border = BorderFactory.createEmptyBorder(20, 0, 20, 0)
            filesListContainer.add(empty)
        } else {
            summaries.forEachIndexed { i, summary ->
                val nextRelpath = summaries.getOrNull(i + 1)?.relpath ?: summaries.getOrNull(i - 1)?.relpath
                filesListContainer.add(fileRow(session, summary, nextRelpath))
            }
        }
        filesListContainer.revalidate()
        filesListContainer.repaint()
    }

    private fun fileRow(session: SessionInfo, summary: FileChangeSummary, nextRelpath: String?): JPanel {
        val foreground = UIManager.getColor("List.foreground")
        val secondaryForeground = UIManager.getColor("Label.disabledForeground")
        val nameColor = when (summary.category) {
            ChangeCategory.NEW -> NEW_FILE_COLOR
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

        // Below MIN_WIDTH_FOR_PATH the Keep/Reject buttons and chevron have no room left if the
        // path is also shown — drop the path first, it's the least essential part of the row. Real
        // child component now (not a painted list cell), so a live resize listener works correctly.
        val parent = file.parent
        var dirLabel: JLabel? = null
        if (parent != null) {
            dirLabel = JLabel(parent)
            dirLabel.foreground = secondaryForeground
            dirLabel.font = dirLabel.font.deriveFont(dirLabel.font.size2D - 1f)
            dirLabel.alignmentX = 0f
            textPanel.add(dirLabel)
        }

        val statsComponent = if (summary.isBinary) {
            val binaryLabel = JLabel("binary")
            binaryLabel.foreground = secondaryForeground
            binaryLabel
        } else {
            val addedLabel = JLabel("+${summary.linesAdded}")
            addedLabel.foreground = ADDED_COUNT_COLOR
            addedLabel.font = addedLabel.font.deriveFont(addedLabel.font.size2D - 1f)
            addedLabel.alignmentX = 0.5f
            val deletedLabel = JLabel("-${summary.linesDeleted}")
            deletedLabel.foreground = DELETED_COUNT_COLOR
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

        val eastPanel = JPanel()
        eastPanel.isOpaque = false
        eastPanel.layout = BoxLayout(eastPanel, BoxLayout.X_AXIS)
        if (summary.reviewStatus == FileReviewStatus.PENDING) {
            val keepButton = PillButton("Keep", KEEP_COLOR)
            val rejectButton = PillButton("Reject", REJECT_COLOR)
            keepButton.addActionListener {
                DiffPresenter.keepAllPending(project, session, summary.relpath)
                refreshFiles()
            }
            rejectButton.addActionListener {
                val wasDiffOpen = DiffPresenter.isDiffTabOpenFor(project, session)
                DiffPresenter.rejectWholeFile(project, session, summary.relpath)
                if (wasDiffOpen && nextRelpath != null) {
                    DiffPresenter.showDiffForFile(project, session, nextRelpath)
                }
                refreshFiles()
            }
            eastPanel.add(keepButton)
            eastPanel.add(Box.createHorizontalStrut(4))
            eastPanel.add(rejectButton)
            eastPanel.add(Box.createHorizontalStrut(8))
        }
        eastPanel.add(chevron)

        val westPanel = JPanel()
        westPanel.isOpaque = false
        westPanel.layout = BoxLayout(westPanel, BoxLayout.X_AXIS)
        westPanel.add(iconLabel)
        westPanel.add(statsComponent)
        westPanel.add(Box.createHorizontalStrut(10))

        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = UIManager.getColor("List.background")
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )
        row.add(westPanel, BorderLayout.WEST)
        row.add(textPanel, BorderLayout.CENTER)
        row.add(eastPanel, BorderLayout.EAST)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)

        // Clicks land on the row itself only when they miss the buttons/chevron (real children now
        // consume their own clicks), so this can't fire when Keep/Reject was actually pressed.
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                DiffPresenter.showDiffForFile(project, session, summary.relpath)
            }

            override fun mouseEntered(e: MouseEvent) {
                row.background = UIManager.getColor("List.selectionBackground")
            }

            override fun mouseExited(e: MouseEvent) {
                row.background = UIManager.getColor("List.background")
            }
        })
        if (dirLabel != null) {
            row.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    dirLabel.isVisible = row.width >= MIN_WIDTH_FOR_PATH
                }
            })
        }

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
