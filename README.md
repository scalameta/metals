# Scalameta language-server

[![](https://travis-ci.org/scalameta/language-server.svg?branch=master)](https://travis-ci.org/scalameta/language-server)

This project is an experiment to implement a [Language Server](https://github.com/Microsoft/language-server-protocol)
for Scala using Scalameta semanticdb and the Scala presentation compiler.


:warning: This project is very alpha stage.
Expect bugs and surprising behavior.
Ticket reports and patches are welcome!

## Project Goals

This project has the following goals:

- a good UX for the final users, including simple installation and setup
- low memory requirements
- integration with scalameta-based tools, such as [Scalafix](https://github.com/scalacenter/scalafix) and [Scalafmt](https://github.com/scalameta/scalafmt)

## Roadmap

Below is a rough summary of what features have been implemented.
Even if some checkbox is marked it does not mean that feature works perfectly.
Some of those features are a likely outside the scope of this project, we are
still learning and exploring what's possible.

* Compile errors with the Scala Presentation Compiler (`textDocument/publishDiagnostics`):
  - [ ] On build compile
  - [x] As you type
* Linting with Scalafix (`textDocument/publishDiagnostics`):
  - [x] On build compile
  - [x] As you type
* Refactoring with Scalafix:
  - [ ] Quick-fix inspections (`textDocument/codeAction`)
  - [ ] Rename local symbol (`textDocument/rename`)
  - [ ] Rename global symbol (`textDocument/rename`)
* Formatting with Scalafmt:
  - [x] Whole file (`textDocument/formatting`)
  - [ ] Selected range (`textDocument/rangeFormatting`)
  - [ ] As you type (`textDocument/onTypeFormatting`)
* Code assistance:
  - [x] Auto-complete symbols in scope as you type (`textDocument/completions`)
  - [ ] Auto-complete global symbol and insert missing imports (`textDocument/completions`)
  - [x] Show parameter list as you type (`textDocument/signatureHelp`)
  - [x] Show type at position (`textDocument/hover`)
* Go to definition with SemanticDB (`textDocument/definition`):
  - [x] Inside the project
  - [x] From project files to Scala dependency source files
  - [x] From project files to Java dependency source files
  - [ ] From project dependency to project dependency
* Find references with SemanticDB (`textDocument/references`):
  - [x] In file (`textDocument/documentHighlight`)
  - [x] In project
  - [ ] In dependencies
* Lookup symbol definition by name:
  - [x] In file (`textDocument/documentSymbol`)
  - [ ] In workspace (`workspace/symbol`)
* Symbol outline:
  - [x] In file as you type (`textDocument/documentSymbol`)

## Contributing

See the [contributing guide](CONTRIBUTING.md).

### Team
The current maintainers (people who can merge pull requests) are:

* Alexey Alekhin - [`@laughedelic`](https://github.com/laughedelic)
* Gabriele Petronella - [`@gabro`](https://github.com/gabro)
* Jorge Vicente Cantero - [`@jvican`](https://github.com/jvican)
* Ólafur Páll Geirsson - [`@olafurpg`](https://github.com/olafurpg)
* Shane Delmore - [`@ShaneDelmore`](https://github.com/ShaneDelmore)

## Acknowledgement
Huge thanks to [`@dragos`](https://github.com/dragos) for his work on a Scala implementation of the LSP (see: https://github.com/dragos/dragos-vscode-scala).
This project helped us get quickly started with LSP.
Since then, we have refactored the project's original sources to the
point where only a few simple case classes remain.

## Related work

- [ENSIME](http://ensime.org): a tool for providing IDE-like features to text editors, that [recently added LSP support](https://github.com/ensime/ensime-server/pull/1888)
