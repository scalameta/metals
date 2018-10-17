---
id: getting-started
title: Contributing to Metals
---

Whenever you are stuck or unsure, please open an issue or
[ask on Gitter](https://gitter.im/scalameta/metals). This project follows
[Scalameta's contribution guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md).

## Project structure

- `metals` contains a Scala implementation of a language server based on
  Scalameta
- `mtags` source file indexer for Java and Scala.
- `vscode-extension` contains a Visual Studio Code extension, implementing a
  client for the language server
- `tests` directory for unit tests
- `test-workspace` directory for manually testing the vscode plugin locally

## Related projects

The improvement you are looking to contribute may belong in a separate
repository:

- [scalameta/scalameta](https://github.com/scalameta/scalameta/): SemanticDB,
  parsing, tokenization
- [scalameta/scalafmt](https://github.com/scalameta/scalafmt/): code formatting
- [scalacenter/scalafix](https://github.com/scalacenter/scalafix/): code
  refactoring and linting
- [scala/scala](https://github.com/scala/scala/): presentation compiler

## Prerequisites

You will need the following applications installed:

- `git`
- `sbt` (for building a local version of the server)
- `npm` (for building a local version of the VSCode extension)
- Visual Studio Code (`code` from the console)

## Unit tests

To run the unit tests open an sbt shell and run `unit/test`

```sh
sbt
# Run once for every change in sbt plugin
> sbt-metals/publishLocal
# Fast (recommended), only specfic test suite
> metals/testOnly -- tests.DefinitionSuite
# Slow (not recommended) run all tests
> unit/test
```

## VS Code extension

Install the extension dependencies

```sh
sbt publishLocal    # Publish the Metals server locally with version number SNAPSHOT
cd vscode-extension
npm install
```

Then open the project

```sh
code .
```

Next, install the Scala syntax package
[`scala-lang.scala`](https://marketplace.visualstudio.com/items?itemName=scala-lang.scala).
This plugin is required to start the extension.

Now you can start the plugin debugging mode via `Debug > Start debugging` or
pressing `F5`. Open the `test-workspace` directory and try to edit some file
like "User.scala".

To see all logs from the Metals server:

```sh
tail -f .metals/metals.log
```

To see the LSP logs to the user open "Output" (macOS `Shift + Cmd + U`) and
select the "Metals" channel.
