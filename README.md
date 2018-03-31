# Metals

[![Join the chat at https://gitter.im/scalameta/scalameta](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/scalameta/scalameta)
[![](https://travis-ci.org/scalameta/metals.svg?branch=master)](https://travis-ci.org/scalameta/metals)

This project is an experiment to implement a
[Language Server](https://github.com/Microsoft/language-server-protocol) for
Scala using [Scalameta](http://scalameta.org/)-related technologies.

:warning: This project is under development and is not intended to be used for
day-to-day coding. Expect bugs and incomplete documentation. Installation
instructions are primarily intended for project contributors.

## Project Goals

* simple installation
* correct diagnostics (red squiggles)
* robust navigation (goto definition anywhere)
* fast completions
* low CPU and memory usage
* refactoring (rename, organize imports, move class, insert type annotation)

## Contributing

See the [installation instructions](BETA.md) and
[contributing guide](CONTRIBUTING.md).

## Roadmap

See the [roadmap](roadmap.md) for a rough summary of what has been implemented
so far.

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

## Related work

* [ENSIME](http://ensime.org): a tool for providing IDE-like features to text
  editors, that
  [recently added LSP support](https://github.com/ensime/ensime-server/pull/1888)

## Why Metals?

Metals = Meta (from Scalameta) + LS (from Language Server)
