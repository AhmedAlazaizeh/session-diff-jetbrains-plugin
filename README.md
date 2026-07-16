# Claude Sessions — JetBrains Plugin

Shows exactly what [Claude Code](https://claude.com/claude-code) changed in a session — never
mixed up with your own uncommitted edits.

`git diff` mixes Claude's edits in with whatever else you were already working on. This plugin
tracks each Claude Code session independently of git, so you can review exactly what Claude
touched, file by file, in the native IDE diff viewer.

## Features

- **Claude Sessions tool window** — every session for the current project, shown as cards
  (title, date, files changed).
- **Per-file change list** — click a session to see the files it touched, each with `+N -M`
  line stats and color-coded new/deleted files.
- **Native diff view** — click any file to open it in the IDE's own diff viewer.
- **Inline gutter markers** — for the latest session, changed lines get colored gutter bars
  directly in the editor, with a per-hunk **Rollback** action (reverts just that hunk, respects
  undo).
- **Git-independent** — works even for files with no baseline in version control, by reading
  Claude Code's own session transcripts and checkpoint data.

## Requirements

- IntelliJ Platform 2026.1+ (`since-build="261"`)
- A project where you've used [Claude Code](https://claude.com/claude-code)

## Installation

Install from the JetBrains Marketplace (search "Claude Sessions"), or build from source below.

## Building from source

```bash
./gradlew buildPlugin
```

Produces an installable zip under `build/distributions/`. Install it via
**Settings → Plugins → ⚙ → Install Plugin from Disk…**.

To run in a sandboxed IDE instance for development:

```bash
./gradlew runIde
```

## How it works

Claude Code checkpoints file content before edits and records tool calls in per-session
transcript files under `~/.claude/projects/<project>/`. This plugin reads that data (plus its
own lightweight snapshot store for files git doesn't track) to reconstruct a precise
before/after baseline per session — without ever touching your own working tree changes.

## License

[MIT](LICENSE)
