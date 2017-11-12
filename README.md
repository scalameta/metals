# Scalameta language-server

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

Below is a rough summary of what features work and don't work.
Some of those features are a likely outside the scope of this project, we are still learning and exploring what's possible.
Please share your thoughts in
[#2](https://github.com/scalameta/language-server/issues/2).

- [x] Linting with Scalafix
- [x] Formatting with Scalafmt
- [x] Auto completions as you type with presentation compiler
- [x] Go to definition from project Scala sources to project Scala sources
- [x] Show type at position
- [x] Go to definition from project sources to Scala dependency source files
- [x] Go to definition from project sources to Java dependency source files
- [ ] Show red squigglies as you type
- [ ] Show red squigglies on compile
- [ ] Show parameter list as you type, signature helper
- [ ] Find symbol references
- [ ] Show docstring on hover
- [ ] Rename symbol

## Contributing

See the [contributing guide](CONTRIBUTING.md).

### Team
The current maintainers (people who can merge pull requests) are:

* Gabriele Petronella - [`@gabro`](https://github.com/gabro)
* Ólafur Páll Geirsson - [`@olafurpg`](https://github.com/olafurpg)

## Acknowledgement
Huge thanks to [`@dragos`](https://github.com/dragos) for his work on a Scala implemenation of the LSP protocol (see: https://github.com/dragos/dragos-vscode-scala).
We've decided to copy the sources over in order to iterate much faster in adding features to the original implementation, with the explicit goal of contributing them back upstream.

## Related work

- [ensime](ensime.org): a tool for providing IDE-like features to text editors, that [recently added LSP support](https://github.com/ensime/ensime-server/pull/1888)
