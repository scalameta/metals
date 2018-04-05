> ⚠️ This project is very alpha stage. Expect bugs and incomplete documentation.

# Contributing

:warning: This project is very alpha, expect bugs! These instructions are
intended for contributors to get a productive workflow when developing the
plugin.

This project follows
[Scalameta's contribution guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md).
Please read them for information about how to create good bug reports and submit
pull requests.

## Project structure

* `lsp4s` contains a Scala implementation of the
  [Language Server Protocol specification](https://github.com/Microsoft/language-server-protocol/blob/master/protocol.md)
* `metals` contains a Scala implementation of a language server based on
  Scalameta, which uses `lsp4s`
* `vscode-extension` contains a Visual Studio Code extension, implementing a
  client for the language server
* `test-workspace` directory for manually testing the plugin locally

## Running a local version of the server
You will need the following applications installed:

* `git`
* `sbt`
* Visual Studio Code (`code` from the console)

```
sbt
> metals/publishLocal     # publish your latest changes locally
                          # keep this sbt shell session open, and
                          # re-run publishLocal every time you
                          # edit *.scala sources.
> ~testWorkspace/test:compile # compile the sources in test-workspace
```

You can then use the Metals VSCode extension published on the Marketplace, and point it to the local
snapshot version of the server you've just published by changing the settings:

```json
"metals.serverVersion": "0.2.0-SNAPSHOT"
```

Then open the metals root with VSCode (`code test-workspace` from the console) and try your changes.

## Running a local version of the VSCode extension
You will need the following applications installed:

* `git`
* `npm`
* Visual Studio Code (`code` from the console)

```
cd vscode-extension
npm install
code .
> Debug > "Start debugging" (shortcut: F5)
          # Inside vscode, F5 will open a new window with the latest
          # metals/publishLocal of the plugin installed.
          # Close the window and run F5 again after every
          # metals/publishLocal
> File > Open > metals/test-workspace (shortcut Cmd+O on macOS)
          # Open the test-workspace folder in the debugging window
          # of vscode. Open a file in the project.
```

You can optionally build the extension and install it for your regular VSCode.

```
npm run build # builds a .vsix extension file
code --install-extension metals-0.1.0.vsix # or whatever the name is
```

To test the plugin on another project than `test-workspace`, you must have the
Scalameta `semanticdb-scalac` compiler plugin enabled.
Refer to the [installation instructions](/docs/installation.md) for details on how to enable it.

## Unit tests

So far, we manually test the integration with vscode/LSP. However, we have a few
unit tests for the parts unrelated to LSP or vscode. To run these tests,

```
sbt
> metals/test                     # Run all unit tests
> metals/testOnly -- tests.mtags  # Only test the mtags tests
> metals/testOnly -- tests.search # Only test the symbol indexer tests
```

## Clearing index cache

This project caches globally in the computed symbol indices from your source
classpath. This is done to prevent reindexing large dependencies like the JDK
(which has ~2.5 million lines of code) on every editor startup. This directory
where this cache is stored depends on your OS and is computed using
[soc/directories](https://github.com/soc/directories) using the project name
"metals". For example, on a Mac this directory is ~/Library/Caches/metals/.
While developing this project, you may encounter the need to need `rm -rf` this
cache directory to re-trigger indexing for some reason.

## Troubleshooting

* If SymbolIndexerTest.classpath tests fail with missing definitions for `List`
  or `CharRef`, try to run `metalsSetup` from the sbt shell and then re-run.
  This command must be re-run after every `clean`.

* If you get the following error

      org.fusesource.leveldbjni.internal.NativeDB$DBException: IO error: lock /path/to/Library/Cache/metals

  that means you are running two metals instances that are competing for the
  same lock on the global cache. Try to turn off your editor (vscode/atom) while
  running the test suite locally. We hope to address this in the future by for
  example moving the cache to each workspace directory or use an alternative
  storing mechanism.

* If you encounter "Error: Channel has been closed" in VSCode, open Settings (Cmd+, on macOS)
and make sure the `"metals.serverVersion"` setting points to an existing version of the server
(either locally or remotely published)
