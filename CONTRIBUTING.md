# Contributing

:warning: This project is very alpha, expect bugs! These instructions are
intended for contributors to get a productive workflow when developing the
plugin.

If anything isn't clear, please open an issue or
[ask on [gitter](https://gitter.im/scalameta/metals)].

This project follows
[Scalameta's contribution guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md).
Please read them for information about how to create good bug reports and submit
pull requests.

## Project structure

* `metals` contains a Scala implementation of a language server based on Scalameta
* `vscode-extension` contains a Visual Studio Code extension, implementing a
  client for the language server
* `test-workspace` directory for manually testing the plugin locally

## Prerequisites
You will need the following applications installed:

* `git`
* `sbt` (for building a local version of the server)
* `npm` (for building a local version of the VSCode extension)
* Visual Studio Code (`code` from the console)

## Running a local version of the server
Open a sbt shell session with

```
sbt
```

We recommend to keep this sbt shell session open.

Publish your latest changes locally and re-run every time you edit *.scala sources.
```
sbt
> ~publishLocal
```

When you're ready to try your new server, compile the sources in test-workspace

```
> ~testWorkspace/test:compile
```

You can then use the Metals VSCode extension [published on the Marketplace](https://marketplace.visualstudio.com/items?itemName=scalameta.metals),
and point it to the local snapshot version of the server you've just published by changing the
setting (<kbd>CMD</kbd> + <kbd>,</kbd> on macOS):

```json
"metals.serverVersion": "SNAPSHOT"
```

Then open the `test-workspace` project with VSCode (`code test-workspace` from the console)
and try your changes.

## Running a local version of the VSCode extension

Install the extension dependencies

```sh
cd vscode-extension
npm install
```

Then open the project

```sh
code .
```

and start a debugging session (`Debug > Start debuggin` or `F5`)

This will open new VSCode window with the latest version of the plugin installed.
(If you also have the plugin from the Marketplace installed, you will get a warning. This is normal)

Then open the `test-workspace` directory (<kbd>CMD</kbd> + <kbd>O</kdb> on macOS) and open a Scala
file. Metals will now start, and you should see the features working after a few seconds.

Close the window and run F5 again after every `publishLocal` of the server.

You can optionally build the modified extension and install it for your regular VSCode.

```sh
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

* If you get the following error

      org.fusesource.leveldbjni.internal.NativeDB$DBException: IO error: lock /path/to/Library/Cache/metals

  that means you are running two metals instances that are competing for the
  same lock on the global cache. Try to turn off your editor (vscode/atom) while
  running the test suite locally. We hope to address this in the future by for
  example moving the cache to each workspace directory or use an alternative
  storing mechanism.

* If you encounter "Error: Channel has been closed" in VSCode, open Settings
(<kbd>CMD</kbd> + <kbd>,</kbd> on macOS)
and make sure the `"metals.serverVersion"` setting points to an existing version of the server
(either locally or remotely published)
