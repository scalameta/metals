---
id: sublime
title: Sublime Text
---

Metals has experimental support for Sublime Text 3 thanks to
[tomv564/LSP](https://github.com/tomv564/LSP).

![Sublime Text demo](../assets/sublime-demo.gif)

```scala mdoc:requirements

```

## Installing the plugin

First, install the LSP plugin:
`Command Palette (Cmd + Shift + P) > Install package > LSP`

```scala mdoc:bootstrap:metals-sublime sublime

```

The `-Dmetals.client=sublime` flag configures Metals for usage with the Sublime
Text LSP client.

Next, update the LSP plugin settings to run `metals-sublime` for Scala sources:
`Command Palette (Cmd + Shift + P) > LSP Settings`. Update the JSON file to
include the Metals server.

```json
{
  "clients": {
    "metals": {
      "command": ["metals-sublime"],
      "enabled": true,
      "languageId": "scala",
      "scopes": ["source.scala"],
      "syntaxes": ["Packages/Scala/Scala.sublime-syntax"]
    }
  }
}
```

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

![Enable Language Server for this project](../assets/sublime-enable-lsp.gif)

This starts the Metal language server but no functionality will work because the
build has not been imported. The first time you enable Language Server in a
project, it prompts you to "Import changes". This step is required for compile
errors and goto definition to work and while it is running, no Metals
functionality will work.

This step can take a long time, especially the first time you run it in a new
workspace. The exact time depends on the complexity of the build and if library
dependencies are cached or need to be downloaded. For example, this step can
take everything from 10 seconds in small cached builds up to 10-15 minutes in
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

The default key binding is `shift+F12`. If you use vim-binding, you need to be
in insert-mode.

![Find references](https://i.imgur.com/BJDkczD.gif)

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

Once configured, the command can be called from the command pallette.

![Import build command](https://i.imgur.com/LViPc95.png)

You can optionally register a key binding for the command.

## Known issues

- The Sublime Text client uses an alert window for `window/showMessage` that
  prevents you from editing code so Metals uses `window/logMessage` instead.

```scala mdoc:generic

```
