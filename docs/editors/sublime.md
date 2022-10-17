---
id: sublime
title: Sublime Text
---

Metals works with Sublime Text (build 4000 or later) thanks to the
[sublimelsp/LSP](https://github.com/sublimelsp/LSP) and [scalameta/metals-sublime](https://github.com/scalameta/metals-sublime) plugins.

![Sublime Text demo](https://i.imgur.com/vJKP0T3.gif)

```scala mdoc:requirements

```

## Installing the plugins

Install the following packages:

- [sublimelsp/LSP](https://github.com/sublimelsp/LSP): Language Server Protocol support for Sublime Text.  
`Command Palette (Cmd + Shift + P) > Install package > LSP`

- [scalameta/metals-sublime](https://github.com/scalameta/metals-sublime): For automatic installation of metals and custom commands. 
`Command Palette (Cmd + Shift + P) > Install package > LSP-metals`

Finally restart sublime text.

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

![Build Import](https://i.imgur.com/eUk30Zy.png)

Open Sublime in the base directory of your Scala project and it will then prompt you to import the build as long as you're using one of the [supported build tools](https://scalameta.org/metals/docs/build-tools/overview.html). Click "Import build" to start the installation step.

This starts the Metal language server but no functionality will work yet because the
build has not been imported. 

This step can take a long time, especially the first time you run it in a new
workspace. The exact time depends on the complexity of the build and if the library dependencies are cached or need to be downloaded. For example, this step can take anywhere from 10 seconds in small cached builds up to 10-15 minutes in large un-cached builds.

## Server logs

For more detailed information about what is happening behind the scenes during
`sbt bloopInstall` run `lsp toggle server panel` in the command palette. You can optionally add key binding for this command.

![Server logs](https://i.imgur.com/PilER2E.png)

Once the import step completes, compilation starts for your open `*.scala`
files. Once the sources have compiled successfully, you can navigate the
sources with "Goto definition" by pressing `F12`.

## Find symbol references

The default key binding is `shift+F12`. If you use vim-bindings, you need to be
in insert-mode.

![Find references](https://i.imgur.com/BJDkczD.gif)

## Goto symbol in workspace

You can search for symbols in your dependency source using the command palette.

![workspace symbols](https://i.imgur.com/8X0XNi2.gif)

## Manually trigger build import

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

#### Keymapping for formatting document via scalafmt

Open "Preferences > Key Binding" and register `ctrl+alt+l` to trigger formatting document.
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

## Using latest Metals SNAPSHOT

Update the "server_version" setting to try out the latest pending Metals
features by accessing `Preferences > Package Settings > LSP > Servers > LSP-metals`

```scala mdoc:releases

```

```scala mdoc:generic

```

```scala mdoc:worksheet
```

```scala mdoc:scalafix

```