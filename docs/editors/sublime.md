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

Next, build a `metals-sublime` binary using the
[Coursier](https://github.com/coursier/coursier) command-line interface.

```sh
coursier bootstrap \
  --java-opt -XX:+UseG1GC \
  --java-opt -XX:+UseStringDeduplication  \
  --java-opt -Xss4m \
  --java-opt -Xms1G \
  --java-opt -Xmx4G  \
  --java-opt -Dmetals.client=sublime \
  org.scalameta:metals_2.12:SNAPSHOT \
  -r bintray:scalacenter/releases \
  -o metals-sublime -f
```

The `-Dmetals.client=sublime` flag configures Metals for usage with the Sublime
Text LSP client.

(optional) Feel free to place this binary anywhere on your `$PATH`, for example
adapt the `-o` flag like this:

```diff
-  -o metals-sublime -f
+  -o /usr/local/bin/metals-sublime -f
```

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
  { "keys": ["f12"], "command": "lsp_symbol_definition" }
]
```

## Importing a build

Open Sublime in the base directory of an sbt build. Run the "Enable Language
Server in project" command.

![Enable Language Server for this project](../assets/sublime-enable-lsp.gif)

This starts the Metal language server but no functionality will work because the
build has not been imported. It is currently not possible to import the build
inside Sublime Text so you will need to visit
[http://localhost:5031](http://localhost:5031/) in your web browser.

![Execute Build Import step](../assets/http-client-import-build.png)

Click on "Import build" to start the `sbt bloopInstall` step. While the
`sbt bloopInstall` step is running, no Metals functionality will work.

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

## Known issues

- [#410](https://github.com/tomv564/LSP/issues/410#issuecomment-439985624) The
  Sublime Text client does not shut down the Metals server. Every time you quit
  Sublime text ensure to kill the background process with the following command
  ```sh
  jps | grep metals-sublime | awk '{ print $1 }'  | xargs kill
  ```
- The Sublime Text client does not implement `window/showMessageRequest` making
  it necessary to import the build via the browser instead of inside the editor.
- The Sublime Text client uses an invasive alert window for
  `window/showMessage`, so Metals uses `window/logMessage` instead.
