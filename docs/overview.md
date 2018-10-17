---
id: overview
title: Project Goals
---

This document describes the project goals of Metals. This document does not
describe existing functionality of Metals but rather what user experience we
want Metals to have in the future.

- [**Simple installation**](#simple-installation): importing a project should be
  simple and require as few steps as possible.
- [**Robust navigation**](#robust-navigation): goto definition should work
  everywhere, even for Java dependencies.
- [**Low CPU and memory usage**](#low-cpu-and-memory-usage): indexing should run
  in the background and not get in your way from coding.

We acknowledge that simple installation and robust navigation alone is not
enough to make a great language server. A great language server should also
support correct diagnostics, fast completions and solid refactorings. However,
we believe navigation is an important first milestone that needs full attention
before working on other features.

## Simple installation

The first you do with an IDE is boring but critical: import a project. The
Language Server Protocol does not provide utilities to extract metadata from a
build tool (source directories, dependencies, compiler flags) so we are still
exploring if we can use [BSP][]: LSP-inspired protocol to standardize on
communication between a language server and build tool. In theory, BSP should
enable automatic importing of projects without custom build plugins or manual
installation steps.

## Robust navigation

Goto definition and find references is a great way to navigate and learn about a
codebase. Metals uses [SemanticDB][], a data model for semantic information
about programs in Scala and other languages, to power code navigation. Indexing
happens with during batch compilation in the build tool, using a similar
architecture as [Index-While-Building][] in XCode 9.

Code navigation is not a single feature, it is a collection of several features
that help you understand a codebase. Below is a list of features that we label
under "code navigation"

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
Our goal in Metals is to support code navigation with with low CPU and memory
overhead.

[semanticdb]: https://scalameta.org/docs/semanticdb/specification.html
[bsp]: https://github.com/scalacenter/bsp/blob/master/docs/bsp.md
