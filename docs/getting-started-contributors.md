---
id: getting-started-contributors
title: Contributing to Metals
---

> ⚠ ️ This project is under development so there is nothing to try out yet.
> These instructions are intended for people who want to contribute to the
> Metals codebase.

If anything isn't clear, please open an issue or
[ask on gitter](https://gitter.im/scalameta/metals).

This project follows
[Scalameta's contribution guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md).
Please read them for information about how to create good bug reports and submit
pull requests.

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
- [scalameta/lsp4s](https://github.com/scalameta/lsp4s/): LSP data structures
  and JSON-RPC
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

```
sbt
> unit/test                                 # Run all unit tests
> metals/testOnly -- tests.DefinitionSuite  # Only test goto definition
```

## VS Code extension

Install the extension dependencies

```sh
cd vscode-extension
npm install
```

Then open the project

```sh
code .
```

and start a debugging session (`Debug > Start debuggin` or `F5`).

> ⚠ It is normal that the plugin fails to start because it tries to start the
> Metals language server, which does not exist at this moment.
