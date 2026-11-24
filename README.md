# CodePad

Local-first code/text editor for Android. No account, no server, no internet permission.

Features: tabs, open/save (SAF, multi-file), crash-proof session + draft recovery, syntax highlighting
(27 languages), line numbers, find/replace (regex), go to line, undo/redo with merging, auto-indent,
symbol bar, line ops (duplicate/delete/move/comment/indent), 6 themes, pinch-zoom, encoding + CRLF/LF
handling, hardware-keyboard shortcuts, open-with / share-to support.

Build: push to GitHub -> Actions -> "Build APK" -> Run workflow -> download app-debug.apk from Releases.

Run files: the toolbar Run button (or F5) runs the open file through a plugin runner. Runners, snippets,
commands and scripts all come from plugins - see docs/PLUGINS.md.
