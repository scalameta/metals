---
id: getting-started
title: Contributing to Metals
---

Whenever you are stuck or unsure, please open an issue or
[ask on Gitter](https://gitter.im/scalameta/metals). This project follows
[Scalameta's contribution guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md).

## Requirements

You will need the following applications installed:

- Java 8, make sure `JAVA_HOME` points to a Java 8 installation and not Java 11.
- `git`
- `sbt` (for building a local version of the server)

## Project structure

- `metals` contains a Scala implementation of a language server based on
  Scalameta.
- `mtags` source file indexer for Java and Scala.
- `tests/input` example Scala code that is used as testing data for unit tests.
- `tests/unit` moderately fast-running unit tests.
- `tests/slow` slow integration tests.
- `test-workspace` demo project for manually testing Metals through an editor.

## Related projects

The improvement you are looking to contribute may belong in a separate
repository:

- [scalameta/scalameta](https://github.com/scalameta/scalameta/): SemanticDB,
  parsing, tokenization.
- [scalacenter/bloop](https://github.com/scalacenter/bloop/): build server for
  compilation.
- [scala/scala](https://github.com/scala/scala/): presentation compiler.
- [scalameta/scalafmt](https://github.com/scalameta/scalafmt/): code formatting.
- [scalacenter/scalafix](https://github.com/scalacenter/scalafix/): code
  refactoring and linting.

## Unit tests

To run the unit tests open an sbt shell and run `unit/test`

```sh
sbt
# Run once in the beginning and run again for every change in the sbt plugin.
> sbt-metals/publishLocal
# (recommended) run specific test suite, great for edit/test/debug workflows.
> metals/testOnly -- tests.DefinitionSuite
# run unit tests, modestly fast but still a bit too slow for edit/test/debug workflows.
> unit/test
# run slow integration tests, takes several minutes.
> slow/test
# (not recommended) run all tests, slow. It's better to target individual projects.
> test
```

## Manual tests

Some functionality is best to manually test through an editor. A common workflow
while iterating on a new feature is to run `publishLocal` and then open an
editor in a small demo build.

Use `tail -f` to watch logs from a running Metals server.

```sh
tail -f .metals/metals.log
```

### Visual Studio Code

First build the VS Code extension from source

```sh
git clone https://github.com/scalameta/metals-vscode.git
cd metals-vscode
npm install
code .
```

Next, install the Scala syntax package
[`scala-lang.scala`](https://marketplace.visualstudio.com/items?itemName=scala-lang.scala).
This plugin is required to start the extension.

Now you can start the plugin debugging mode via `Debug > Start debugging` or
pressing `F5`. Open the a small demo project (for example `test-workspace` in
the Metals repo) and try to edit some file like `*.scala` files.

To see the LSP logs to the user open "Output" (macOS `Shift + Cmd + U`) and
select the "Metals" channel.

When you make changes in the Metals Scala codebase, re-run `publishLocal` in the
Metals sbt build, quit VS Code in the extension host and restart the extension
with `F5` (or `Debug > Start debugging`).

### Vim

First, follow the [`vim` installation instruction](../editors/vim.html).

Next, write a `new-metals-vim` script that builds a new `metals-vim` bootstrap
script using the locally published version.

```sh
coursier bootstrap \
  --java-opt -Dmetals.client=vim-lsc \
  org.scalameta:metals_2.12:@LOCAL_VERSION@ \ # double-check version here
  -r bintray:scalacenter/releases \
  -o /usr/local/bin/metals-vim -f
```

Finally, start vim with the local Metals version

```sh
cd test-workspace # any directory you want to manually test Metals
new-metals-vim && vim build.sbt
```

When you make changes in the Metals Scala codebase, quit vim and re-run
`new-metals-vim && vim build.sbt`.
