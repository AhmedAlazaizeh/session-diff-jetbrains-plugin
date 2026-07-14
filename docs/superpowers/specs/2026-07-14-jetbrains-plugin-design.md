# session-diff JetBrains plugin — design

## Context

`session-diff` (the existing Claude Code CLI tool, at
`gitlab.progressoft.io/ps.Ahmad.Alazaizeh/session-diff`) already shows a diff
of only what Claude changed in a session, opened in the JetBrains diff
viewer via shelling out to `idea diff before/ after/`. It works, but every
invocation needs a trigger (`/session-diff`, `!csdiff`, or the `csdiff` shell
command) and only shows one session at a time (current, or a specific
`--session-id`).

Researched `touwaeriol/claude-code-plus` (a JetBrains plugin wrapping Claude
Code) for ideas. It uses IntelliJ's LocalHistory API rather than git-tracked
status. Investigated switching to that, but rejected it: LocalHistory's own
revisions are event-triggered, not per-edit, which is why `claude-code-plus`
places explicit `putSystemLabel()` markers at the moment of each edit — they
can do that because they intercept the edit themselves (a custom tool
implementation). We don't; the user runs the vanilla `claude` CLI, external
to the IDE. Matching that precision would mean an external hook signaling
the live plugin process in real time (new IPC surface) for a benefit
(not managing our own snapshot store) that isn't worth the complexity given
Round 7's hook-based store already works, is verified end-to-end, and needs
no IPC.

Goal: a proper IntelliJ Platform plugin — a tool window listing Claude Code
sessions for the current project, click one to open a native multi-file
diff, no manual command needed.

## Architecture

A Kotlin IntelliJ Platform plugin (built with Gradle, the standard
`intellij-platform-plugin-template` toolchain). Targets the IntelliJ
Platform generally (not IDE-specific APIs), so it works in IntelliJ IDEA,
WebStorm, PyCharm, and any other JetBrains IDE without extra work.

No Python dependency anywhere in the plugin. It reads the same on-disk data
`session-diff.py` already reads — Claude Code's transcript JSONL files and
our own Round-7 snapshot store — directly in Kotlin.

## Components

**`SessionDiscoveryService`**
- Given the current project's base path, computes
  `~/.claude/projects/<encoded-path>/` the same way `session-diff.py`'s
  `find_transcript` does (`/` → `-`).
- Lists `.jsonl` files there, parses each far enough to extract: session id
  (filename stem), start timestamp, and touched-file count (a Kotlin port of
  `collect_changes`'s tool_use scanning — looks for `Edit`/`Write`/
  `NotebookEdit` blocks in `message.content`).
- Malformed/partial JSON lines are skipped, matching the Python script's
  `try/except json.JSONDecodeError` behavior.

**`SessionListToolWindow`**
- A `ToolWindowFactory` registering a dedicated tool window ("Claude
  Sessions"), scoped to the current project only.
- Content: a list, one row per discovered session, newest first, labeled
  `<time> · <N> files` (e.g. "10:52 · 3 files").
- Live-updates via a `BulkFileListener`/VFS listener watching the project's
  transcript directory — no polling loop.

**`BaselineResolver`**
- Given a session id and an absolute file path, resolves the pre-edit
  baseline with the same priority order as `session-diff.py`:
  1. Our own store: `~/.claude/session-diff-history/<session-id>/<sha256(path)>`
     (Round 7's hook writes these; this component only reads them).
  2. Fallback: Claude Code's own `file-history-snapshot` /
     `trackedFileBackups` checkpoint data, for sessions that predate the
     Round 7 hook.
  3. Last resort: if neither has anything, shell out to
     `git status --porcelain=v1` (git itself is fine to depend on — it's
     the Python dependency we're removing, not git) and surface an
     untracked-file warning rather than silently treating the whole file
     as "added."

**`DiffPresenter`**
- On row click: re-parse that session's touched files, resolve each
  baseline via `BaselineResolver`, and build `DiffContent` objects directly
  from in-memory byte arrays (baseline bytes + current file bytes read
  straight off disk) — no shadow-tree directories on disk at all. That
  whole `/tmp/claude-session-diff/<session>/{before,after}` step in the
  Python version only existed to feed the external `idea diff` CLI; calling
  `DiffManager` in-process removes the need for it entirely.
- Opens a multi-file `DiffRequestChain` via
  `DiffManager.getInstance().showDiff(...)` — the native equivalent of the
  CLI's folder-diff view, with prev/next file navigation built in.

## Data flow

Project opens → `SessionDiscoveryService` resolves and starts watching its
transcript directory → tool window populates and stays live → user clicks a
session row → touched files re-extracted from that transcript → each file's
baseline resolved → `DiffContent` pairs built → native multi-file diff tab
opens.

## Error handling

All mirroring behavior already proven in `session-diff.py` today:

- No `~/.claude/projects/<encoded>/` directory at all → empty list state
  ("No Claude Code sessions found for this project").
- Malformed JSONL lines → skipped, not fatal.
- Touched files outside the project root (the exact bug hit and fixed in
  Round 6 of the CLI tool) → excluded from the diff, noted separately, not
  silently dropped and not crashing.
- Bash-driven `rm`/`mv`/`git mv` → flagged in an info banner (same
  regex-based detection as `session-diff.py`'s `DESTRUCTIVE_BASH_RE`,
  ported to Kotlin) — these are never diffable, only flagged for manual
  check.
- Untracked-file-with-no-baseline → a warning badge on that file's diff
  tab, not a blocking error — matches the CLI tool's existing behavior.

## Testing

No formal unit-test framework for this first pass — personal-use plugin,
proportionate effort. Manual verification checklist, installed via
"Install Plugin from Disk" in a real IntelliJ instance against the same
regression cases already exercised building the CLI tool:

- Untracked file with a Round-7 hook snapshot → precise line-level diff.
- Staged-but-uncommitted file → precise line-level diff (no warning).
- File touched outside the project root → excluded, not crashed.
- Session with a Bash `rm`/`mv` → flagged, not silently included.
- Fully untracked file with no snapshot at all (pre-hook session) →
  untracked warning badge shown, whole-file diff.

## Distribution

New repo: `gitlab.progressoft.io/ps.Ahmad.Alazaizeh/session-diff-jetbrains-plugin`
(private — same visibility constraint as before, "internal" is blocked by
GitLab admin policy). Not published to JetBrains Marketplace. Built via
Gradle, installed manually via Settings → Plugins → Install Plugin from
Disk, same as any local `.zip` plugin.
