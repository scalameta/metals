---
id: overview
title: Overview
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
exploring what is the best option.

- sbt-metals: plugin that provides a `metalsSetup` task to export sbt build
  metadata.
- [BSP][]: LSP-inspired protocol to standardize on communication between a
  language server and build tool. In theory, should enable automatic importing
  of projects without custom build plugins or manual installation steps.

## Robust navigation

Goto definition and find references is a great way to navigate and learn about a
codebase. Metals uses [SemanticDB][], a data model for semantic information
about programs in Scala and other languages, to power code navigation. Indexing
happens with during batch compilation in the build tool, using a similar
architecture as [Index-While-Building][] in XCode 9.

- Goto definition (`textDocument/definition`). Requires
  [semanticdb-scalac](#semanticdb-scalac).
  - project -> project.
  - project -> Scala dependency. Requires [`metalsSetup`](#metalssetup).
  - project -> Java dependency. Requires [`metalsSetup`](#metalssetup).
  - dependency -> dependency.
- Find references (`textDocument/references`). Works the same as goto
  definition.
  - project references
  - Scala dependency references
  - Java dependency references
- Highlight references to symbol at position (`textDocument/documentHighlight`).
- Disabled by default. Can be enabled with configuration option
  `highlight.enabled=true`.
- Goto symbol in file (`textDocument/documentSymbol`)
- Goto symbol in workspace (`workspace/symbol`)
- Symbol outline in the sidebar as you type (`textDocument/documentSymbol`).
- Goto implementation (`textDocument/implementation`)
- Goto type definition (`textDocument/typeDefinition`)
- Show type of symbol at position (`textDocument/hover`).
- Show type of expression at position (`textDocument/hover`).

## Low CPU and memory usage

Heavy resource usage is one of the most frequent complaints about IDEs. Low CPU
and memory usage becomes even more important when working in larger codebases.

- Persistent symbol index: currently Metals stores the navigation index
  in-memory, which can consume multiple GB of RAM for large projects.
- Faster Scala and Java outline indexing. Currently, Metals can index the
  outlines of Scala sources at ~30-40k loc/s and Java sources at ~450k loc/s.
  Indexing should be able to process ~500k loc/s.
- Throttled background indexing. Currently, all available CPUs are utilized for
  indexing, which makes the computer slow for a period after running
  `metalsSetup`.
- Faster "open symbol in workspace" (`workspace/symbol`). Currently, uses a
  naive linear search algorithm that is slow for large projects.

## Glossary

### metalsSetup

Task in sbt-metals to generate build metadata. Run `metalsSetup` to generate
metadata for all modules in the build, including main and test sources.

### semanticdb-scalac

Compiler plugin that emits [SemanticDB][] files during batch compilation in the
build tool. Overhead of the compiler plugin is ~30%, which may improve in the
future.

[semanticdb]:
  https://github.com/scalameta/scalameta/blob/master/semanticdb/semanticdb3/semanticdb3.md
[index-while-building]: https://youtu.be/jGJhnIT-D2M
[bsp]: https://github.com/scalacenter/bsp/blob/master/docs/bsp.md
