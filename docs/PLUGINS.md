# CodePad plugins

A plugin is **one JSON file**. Install it from *Settings → Plugins → Install plugin from file*.
Bundled plugins are ordinary plugins stored in the same folder, so you can copy and modify them.

```json
{
  "id": "my.hello-plugin",            // required: 3-64 chars, lowercase letters, digits, . _ -
  "name": "Hello Plugin",
  "version": "1.0.0",
  "publisher": "me",
  "description": "What it does",
  "engine": 1,                        // plugin API version (CodePad refuses newer ones)
  "permissions": ["editor.read", "editor.write", "ui"],
  "activationEvents": ["onStartup"],  // optional, see below
  "contributes": { "runners": [], "snippets": [], "commands": [] },
  "script": "codepad.commands.register('hello.shout', function () { ... });"
}
```

## Permissions
Declared up front, shown to you before install, enforced on every API call.

| permission     | allows |
|----------------|--------|
| `editor.read`  | read the open file's text, selection, name, language |
| `editor.write` | replace the selection / the whole text |
| `ui`           | messages, input boxes, the Output panel |
| `run.termux`   | run commands in Termux (powerful: anything Termux can do) |
| `run.sandbox`  | run code in the built-in sandbox (no network, no files) |

## Contribution points

### `runners` - what the Run button does
```json
{ "id": "python", "title": "Python", "languages": ["Python"], "extensions": ["py"],
  "type": "termux", "command": "python \"${file}\"", "hint": "pkg install python" }
```
Types: `termux`, `sandbox-js`, `preview-html`, `preview-markdown`.

* `termux`: needs permission `run.termux`. The file is recreated inside Termux (`~/.codepad/run/`), then `command` runs there.
  Variables: `${file}` `${fileName}` `${fileBase}` `${fileDir}` `${home}`.
  `"mode": "terminal"` opens a visible Termux session (for programs that read input). Default `background` captures output into the Output panel.
  `hint` is shown when the command is not found.
* `sandbox-js`: runs JavaScript in an isolated WebView, `console.log` goes to the Output panel. `"timeoutMs"` waits for timers (default 1500).
* `preview-html` / `preview-markdown`: opens a rendered preview. No network access.

Run with the toolbar button or F5 / Ctrl+Enter. With several runners for a language, "Run with..." in the menu lets you pick; the choice is remembered per language.

### `snippets`
```json
{ "language": "Python", "prefix": "def", "name": "function", "body": "def ${1:name}(${2}):\n    ${3:pass}$0" }
```
Type the prefix and press **Tab**, or use *Insert snippet*. `language` may be `"*"`.
Supports `$1`, `${1:default}` (the lowest number is selected first), `$0` (final cursor) and `\$` for a literal dollar.

### `commands`
```json
{ "id": "hello.shout", "title": "Hello: UPPERCASE selection" }
```
Appear in the **Command palette** (menu, or Ctrl+Shift+P). A command either points at a runner (`"runner": "python"`) or is handled by the plugin's `script`.

## Scripts
`script` is JavaScript run in a hidden sandbox with **no network and no file access**. The only global is `codepad`:

```js
codepad.commands.register(id, fn)                 // handle a contributed command
codepad.window.showMessage(text)                  // ui
codepad.window.showError(text)                    // ui
codepad.window.showInputBox(prompt, cb)           // ui; cb(value) - value is null if cancelled
codepad.editor.getText()                          // editor.read
codepad.editor.getSelectedText()                  // editor.read
codepad.editor.getFileName()                      // editor.read
codepad.editor.getLanguage()                      // editor.read
codepad.editor.replaceSelection(text)             // editor.write (inserts at the cursor if nothing is selected)
codepad.editor.replaceAll(text)                   // editor.write
codepad.output.append(text) / clear() / show()    // ui
codepad.terminal.run(cmd, cb)                     // run.termux; cb({ stdout, stderr, exitCode, error })
```

Calling something you did not declare a permission for throws an error.

### Activation
Scripts load lazily, the first time one of their commands is run (VS Code's `onCommand`). Optional `activationEvents`:
`"onStartup"` (load when CodePad starts) and `"onLanguage:Python"` (load when a file of that language is shown).

## Termux setup (for `termux` runners)
1. Install Termux from F-Droid.
2. In Termux: `mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties`, then fully close and reopen Termux.
3. Android Settings → Apps → CodePad → Permissions → allow "Run commands in Termux environment" (CodePad also asks the first time).
4. Install what you need, e.g. `pkg install python nodejs clang`.

Limit: files over ~60 KB are not sent to Termux.
