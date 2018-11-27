---
id: project-goals
title: Project Goals
---

This document describes the project goals of Metals. This document does not
describe existing functionality of Metals but rather what user experience we
want Metals to have in the future.

- [**Simple installation**](#simple-installation): importing a project should be
  simple and require as few steps as possible.
- [**Robust navigation**](#robust-navigation): goto definition should work
  everywhere, including for external Scala/Java dependencies.
- [**Low CPU and memory usage**](#low-cpu-and-memory-usage): indexing should run
  in the background and not get in your way from coding.

We acknowledge that simple installation and robust navigation alone is not
enough to make a great language server. A great language server should also
support correct diagnostics, fast completions and solid refactorings. However,
we believe navigation is an important first milestone that needs full attention
before focusing on other features.

## Simple installation

The first thing you do with an IDE is boring but critical: import a project. The
[Language Server Protocol (LSP)](https://microsoft.github.io/language-server-protocol/specification)
does not provide utilities to extract metadata such as source directories,
dependencies and compiler flags from a build tool. Instead, Metals uses the
[Build Server Protocol (BSP)](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md)
to standardize on communication between a language server and build tool. The
same way LSP allows Metals to support multiple editors with a unified interface,
BSP allows Metals to import projects from multiple build tools with the same
interface.

## Robust navigation

Goto definition and find references is a great way to navigate a codebase and
understand how it works. Metals uses [SemanticDB](https://scalameta.org/) to
power code navigation, SemanticDB is a data model for semantic information about
programs in Scala and other languages. Indexing happens during batch compilation
in the build tool using a similar architecture as
[Index-While-Building](https://www.youtube.com/watch?v=jGJhnIT-D2M), which is
used in Xcode 9 and
[sourcekit-lsp](https://github.com/apple/sourcekit-lsp#indexing-while-building).

Code navigation is not a single feature, it is a collection of several features
that help you understand a codebase. Below is a list of features that Metals
considers as "code navigation".

- Goto definition (`textDocument/definition`).
  - project -> project.
  - project -> Scala dependency.
  - project -> Java dependency.
  - dependency -> dependency.
- Find references (`textDocument/references`).
  - project sources
  - Scala dependency sources
  - Java dependency sources
- Highlight references to a symbol in current buffer
  (`textDocument/documentHighlight`).
- Goto symbol in file (`textDocument/documentSymbol`)
- Goto symbol in workspace (`workspace/symbol`)
- Symbol outline in the sidebar of current buffer
  (`textDocument/documentSymbol`).
- Goto implementation (`textDocument/implementation`)
- Goto type definition (`textDocument/typeDefinition`)
- Show type of symbol at position (`textDocument/hover`).
- Show type of expression at position (`textDocument/hover`).

## Low CPU and memory usage

Heavy resource usage is one of the most frequent complaints about IDEs. Low CPU
and memory usage becomes even more important when working in larger codebases.
Our goal in Metals is to support code navigation with as low CPU and memory
overhead as possible without sacrificing rich functionality.

[semanticdb]: https://scalameta.org/docs/semanticdb/specification.html
[bsp]: https://github.com/scalacenter/bsp/blob/master/docs/bsp.md
