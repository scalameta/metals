# Metals

[![Join the chat at https://gitter.im/scalameta/metals](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/scalameta/metals)
[![](https://travis-ci.org/scalameta/metals.svg?branch=master)](https://travis-ci.org/scalameta/metals)

This project is an experiment to implement a
[Language Server](https://github.com/Microsoft/language-server-protocol) for
Scala using [Scalameta](http://scalameta.org/) projects such as
[Scalafmt](http://scalameta.org/scalafmt/),
[Scalafix](https://scalacenter.github.io/scalafix/) and
[SemanticDB](https://github.com/scalameta/scalameta/blob/master/semanticdb/semanticdb3/semanticdb3.md).

:warning: This project is under development and is not intended to be used for
day-to-day coding. Expect bugs and incomplete documentation. Installation
instructions are primarily intended for project contributors.

## Contributing

See the [contributing guide](https://scalameta.org/metals/docs/getting-started-contributors.html).

## Overview

See [here](https://scalameta.org/metals/docs/overview.html) for an overview of a what features are supported or
not supported by Metals.

### Team

The current maintainers (people who can merge pull requests) are:

* Alexey Alekhin - [`@laughedelic`](https://github.com/laughedelic)
* Gabriele Petronella - [`@gabro`](https://github.com/gabro)
* Jorge Vicente Cantero - [`@jvican`](https://github.com/jvican)
* Ólafur Páll Geirsson - [`@olafurpg`](https://github.com/olafurpg)
* Shane Delmore - [`@ShaneDelmore`](https://github.com/ShaneDelmore)

## Acknowledgement

Huge thanks to [`@dragos`](https://github.com/dragos) for his work on a Scala
implementation of the LSP (see: https://github.com/dragos/dragos-vscode-scala).
This project helped us get quickly started with LSP. Since then, we have
refactored the project's original sources to the point where only a few simple
case classes remain.

## Alternatives

* [IntelliJ IDEA](https://www.jetbrains.com/help/idea/discover-intellij-idea-for-scala.html):
  the most widely used IDE for Scala using a re-implementation of the Scala
  typechecker.
* [ENSIME](http://ensime.org): brings Scala and Java IDE-like features to
  editors like Emacs and Sublime Text using the Scala Presentation Compiler.
* [Scala IDE](http://scala-ide.org/): Eclipse-based IDE using the Scala
  Presentation Compiler.

## Why Metals?

Metals = Meta (from Scalameta) + LS (from Language Server)
