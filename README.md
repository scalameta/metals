# Scalameta language-server

This project is an experiment to implement a [Language Server](https://github.com/Microsoft/language-server-protocol)
for Scala using Scalameta semanticdb and the Scala presentation compiler.


:warning: This project is very alpha stage.
Expect bugs and surprising behavior.
Ticket reports and patches are welcome!


## Roadmap

Below is a rough summary of what features work and don't work.
Some of those features are a likely outside the scope of this project, we are still learning and exploring what's possible.
Please share your thoughts in
[#2](https://github.com/scalameta/language-server/issues/2).

- [x] Linting with Scalafix
- [x] Formatting with Scalafmt
- [x] Auto completions as you type with presentation compiler
- [x] Go to definition from project Scala sources to project Scala sources with Semanticdb
- [x] Show type at position
- [ ] Go to definition from project sources to dependency sources
- [ ] Go to definition from dependency sources to dependency sources
- [ ] Go to definition in Java sources
- [ ] Show red squigglies as you type
- [ ] Show red squigglies on compile
- [ ] Show parameter list as you type, signature helper
- [ ] Find symbol references
- [ ] Show docstring on hover

## Contributing

See the [contributing guide](CONTRIBUTING.md).

### Team
The current maintainers (people who can merge pull requests) are:

* Gabriele Petronella - [`@gabro`](https://github.com/gabro)
* Ólafur Páll Geirsson - [`@olafurpg`](https://github.com/olafurpg)

