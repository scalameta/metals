---
id: sublime
title: Sublime Text
---

Metals has experimental support for Sublime Text 3 thanks to
[tomv564/LSP](https://github.com/tomv564/LSP).

![Sublime Text demo](https://i.imgur.com/vJKP0T3.gif)

```scala mdoc:requirements

```

## Installing the plugin

First, install the LSP plugin:
`Command Palette (Cmd + Shift + P) > Install package > LSP`

```scala mdoc:bootstrap:metals-sublime sublime

```

The `-Dmetals.client=sublime` flag configures Metals for usage with the Sublime
Text LSP client.

Next, open "Preferences > Key Binding" and register `F12` to trigger goto
definition.

```json
[
  // ...
  {
    "keys": ["f12"],
    "command": "lsp_symbol_definition"
  }
]
```

## Importing a build

Open Sublime in the base directory of an sbt build. Run the "Enable Language
Server in project" command.

![Enable Language Server for this project](https://i.imgur.com/3c0ZSZm.gif)

This starts the Metal language server but no functionality will work because the
build has not been imported. The first time you enable Language Server in a
project you must open a `*.scala` file, and then it will prompt you to "Import changes".
This step is required for compile errors and goto definition to work. While it's
running, no Metals functionality will work.

This step can take a long time, especially the first time you run it in a new
workspace. The exact time depends on the complexity of the build and if the library
dependencies are cached or need to be downloaded. For example, this step can
take anywhere from 10 seconds in small cached builds up to 10-15 minutes in
large uncached builds.

For more detailed information about what is happening behind the scenes during
`sbt bloopInstall`:

```
tail -f .metals/metals.log
```

Once the import step completes, compilation starts for your open `*.scala`
files. Once the sources have compiled successfully, you can navigate the the
sources with "Goto definition" by pressing `F12`.

## Find symbol references

The default key binding is `shift+F12`. If you use vim-bindings, you need to be
in insert-mode.

![Find references](https://i.imgur.com/BJDkczD.gif)

## Goto symbol in workspace

You can search for symbols in your dependency source using the command palette   

![workspace symbols](https://i.imgur.com/8X0XNi2.gif)

## Manually trigger build import

You can configure a custom command "Metals: Import Build" to manually trigger
build import when changing `build.sbt`. To learn about Sublime Text commands see
[here](http://docs.sublimetext.info/en/latest/reference/command_palette.html).

Update the contents of your `*.sublime-commands` file to include the following
command.

```json
[
  // ...
  {
    "caption": "Metals: Import Build",
    "command": "lsp_execute",
    "args": { "command_name": "build-import" }
  }
]
```

If you don't have a existing `.sublime-commands` file, you can create a new one
in this location.

```sh
# macOS
~/Library/Application\ Support/Sublime\ Text\ 3/Packages/Metals.sublime-commands
# Ubuntu
~/.config/sublime-text-3/Packages/Metals.sublime-commands
```

Once configured, the command can be called from the command palette.

![Import build command](https://i.imgur.com/LViPc95.png)

You can optionally register a key binding for the command.

## Tweaking Sublime Text for a better productivity

This paragraph contains a few tips & trick that can improve your daily productivity with Metals.

### Optional LSP client tweaks

If you prefer to only enable Metals completions
(without mixing them with the default ones from Sublime) set the following setting
in the "Preferences > Preferences: LSP Settings":

```json
{
  // ...
  "only_show_lsp_completions": true,
}
```

Also, if you prefer to show symbol references in Sublime's quick panel instead of the bottom panel
set following setting in the "Preferences > Preferences: LSP Settings":

```json
{
  // ...
  "show_references_in_quick_panel": true,
}
```

![Symbol references in the popup](https://i.imgur.com/7tSiEfX.gif
)

### Additional key mappings 

You can set a few optional key mappings for enable useful action shortcuts and perform some tweaks for the completion popup.

#### Keymaping for formatting document via scalafmt

Open "Preferences > Key Binding" and register `ctrl+alt+l` to trigger formating document.
definition.

```json
[
  // ...
  {
    "keys": ["ctrl+alt+l"],
    "command": "lsp_format_document"
  }
]
```
![Add key mapping for formatting document via scalafmt](https://i.imgur.com/wVjC1Ij.gif)


### Add key mapping for Goto symbol in workspace

This an optional step if you want to have a shortcut for looking up symbols in the workspace.
Open "Preferences > Key Binding" and add:

```json
[
  // ...
  { 
    "keys": ["ctrl+t"], 
    "command": "show_overlay",
    "args": {"overlay": "command_palette", "command": "lsp_workspace_symbols" }
  }
]
```

### Enabling auto-import on completion

Metals can complete symbols from your workspace scope and automatically import them.
By default, however, if you hit "Enter" to select a completion, the LSP client will
complete the class without importing it, but you can easy remap to use also "Enter" key.
Open "Preferences > Key Binding" and add:

```json
[ 
  // ...
  { "keys": ["enter"], "command": "commit_completion", "context": [{ "key": "auto_complete_visible" } ] },
  { "keys": ["tab"], "command": "commit_completion", "context": [{ "key": "auto_complete_visible" } ] }
]
```


![Import after Enter key was hit](https://i.imgur.com/RDYx9mB.gif)

```scala mdoc:generic

```
