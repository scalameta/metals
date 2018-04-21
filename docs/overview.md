---
id: overview
title: Overview
---

This document is an overview of the Metals project. Metals is a community driven
project with no official funding or dedicated resources.

* [**Simple installation**](#simple-installation): importing a project should be
  simple and require as few steps as possible.
* [**Correct diagnostics**](#correct-diagnostics): the editor should not show
  spurious red squiggles, even at the cost of higher latency.
* [**Robust navigation**](#robust-navigation): goto definition should work
  everywhere, even for Java dependencies.
* [**Fast completions**](#fast-completions): completions suggestions should
  respond in ~200ms, even at the cost of occasionally wrong results.
* [**Low CPU and memory usage**](#low-cpu-and-memory-usage): indexing should run
  in the background and not get in your way from coding.
* [**Solid refactoring**](#solid-refactoring): rename, organize imports and
  insert type annotation should always leave the codebase in a compiling state.

## Simple installation

The first you do with an IDE is boring but critical: import a project. The
Language Server Protocol does not provide utilities to extract metadata from a
build tool (module source files, dependencies, configuration data), so we are
still exploring what is the best option.

* [x] sbt-metals: plugin that provides a `metalsSetup` task to export sbt
    build metadata.

* [ ] [BSP][]: LSP-inspired protocol to standardize on communication between a
  language server and build tool. In theory, should enable automatic
  importing of projects without custom build plugins or manual installation
  steps.

## Correct diagnostics

Red squiggles are one of the most basic but also difficult-to-support features
in an IDE. The Scala compiler was not designed for interactivity, which makes
fast and correct diagnostics difficult to support in the editor. We are still
unsure what the best model is for diagnostics, so we are still exploring what is
the best option.

* [x] Scala Presentation Compiler: interactively publish squiggles as you type.
      Provides low latency but may get off-sync with build diagnostics.
  * Disabled by default. Can be enabled with configuration option
    `scalac.diagnostics.enabled=true`.
  * Requires [`metalsSetup`](#metalssetup).
* [x] sbt server: sbt 1.1 added LSP support so that build errors are reported
      directly to the editor. Metals can connect to sbt server as a client to
      trigger batch compilation on file save. Guarantees diagnostics are always
      in-sync with build diagnostics but at the cost of higher latency.
  * Disabled by default. Can be enabled with configuration option
    `sbt.enabled=true`.
  * Requires a running sbt 1.1 server or later.
* [x] Linting with [Scalafix][].
  * Requires [semanticdb-scalac](#semanticdb-scalac).
  * Supports both built-in and custom linter rules.

## Robust navigation

Goto definition and find references is a great way to navigate and learn about a
codebase. Metals uses [SemanticDB][], a data model for semantic information
about programs in Scala and other languages, to power code navigation. Indexing
happens with during batch compilation in the build tool, using a similar
architecture as [Index-While-Building][] in XCode 9.

* Goto definition (`textDocument/definition`). Requires
  [semanticdb-scalac](#semanticdb-scalac).

  - [x] project -> project.

  - [x] project -> Scala dependency. Requires [`metalsSetup`](#metalssetup).

  - [x] project -> Java dependency. Requires [`metalsSetup`](#metalssetup).

  - [ ] dependency -> dependency.

* [x] Find references (`textDocument/references`). Works the same as goto
      definition.

  - [x] project references

  - [ ] Scala dependency references

  - [ ] Java dependency references

* [x] Highlight references to symbol at position
      (`textDocument/documentHighlight`).

  * Disabled by default. Can be enabled with configuration option
    `highlight.enabled=true`.

* [x] Goto symbol in file (`textDocument/documentSymbol`)

* [x] Goto symbol in workspace (`workspace/symbol`)

* [x] Symbol outline in the sidebar as you type (`textDocument/documentSymbol`).

* [ ] Goto implementation (`textDocument/implementation`)

* [ ] Goto type definition (`textDocument/typeDefinition`)

* [x] Show type of symbol at position (`textDocument/hover`). Requires
      [semanticdb-scalac](#semanticdb-scalac).

* [ ] Show type of expression at position (`textDocument/hover`).

## Fast completions

Auto completions are a great way to explore a library interface and save
keystrokes while writing code. Completions are sensitive to latency since
suggestions update on every keystroke. Correct and fast completions are
difficult to support in Scala due to several challenges:

1.  the presentation compiler does not lend itself well to interactive editing
2.  some completions semantics are difficult to reproduce outside of the
    compiler
    * extension methods depend on implicit conversions
    * whitebox macros can refine result types which influences completion
      suggestions

Given these challenges, we are still exploring how to best support correct and
fast completions.

* [x] Scala Presentation Compiler. Faithfully reproduces full language semantics
      but may be slow and difficult to keep in-sync with the build for
      long-running interactive sessions.
  * Disabled by default. Can be enabled with configuration option
    `scalac.completions.enabled=true`.
  * Requires [`metalsSetup`](#metalssetup).
  * Shows parameter list as you type (`textDocument/signatureHelp`).
* [ ] Auto-import global symbol from classpath.
* [ ] Auto-expand abstract methods when implementing a trait or abstract class.

## Low CPU and memory usage

Heavy resource usage is one of the most frequent complaints about IDEs. Low CPU
and memory usage becomes even more important when working in larger codebases.

* [ ] Persistent symbol index: currently Metals stores the navigation index
      in-memory, which can consume multiple GB of RAM for large projects.
* [ ] Faster Scala and Java outline indexing. Currently, Metals can index the
      outlines of Scala sources at ~30-40k loc/s and Java sources at ~450k
      loc/s. Indexing should be able to process ~500k loc/s.
* [ ] Throttled background indexing. Currently, all available CPUs are utilized
      for indexing, which makes the computer slow for a period after running
      `metalsSetup`.
* [ ] Faster "open symbol in workspace" (`workspace/symbol`). Currently, uses a
      naive linear search algorithm that is slow for large projects.

## Solid refactoring

Refactoring makes you more productive while editing code. On demand refactoring
such as rename or formatting are great to save keystrokes for frequent
mechanical tasks. Passive refactoring suggestions help you learn new language
best practices.

* Format (`textDocument/formatting`) with [Scalafmt][].
  * [x] Whole file on demand (`textDocument/formatting`)
  * [ ] Selected range (`textDocument/rangeFormatting`)
  * [x] On save (`textDocument/willSaveWaitUntil`)
  * [ ] As you type (`textDocument/onTypeFormatting`)
  * Scalafmt version can be configured with `scalafmt.version="1.3.0"`.
  * NOTE. The first time Scalafmt is invoked is slow since it requires
    downloading the configured Scalafmt version with Coursier.
* [x] Rename local symbol. Requires [semanticdb-scalac](#semanticdb-scalac).
* [ ] Rename global symbol.
* [x] Remove unused imports (`textDocument/codeActions`). Requires
      [semanticdb-scalac](#semanticdb-scalac).
* [ ] Move class.
* [ ] Organize imports.
* [ ] Insert type annotation.

## Glossary

### metalsSetup

Task in sbt-metals to generate build metadata. Run `metalsSetup` to generate
metadata for all modules in the build, including main and test sources.

### semanticdb-scalac

Compiler plugin that emits [SemanticDB][] files during batch compilation in the
build tool. Overhead of the compiler plugin is ~30%, which may improve in the
future.

[semanticdb]: https://github.com/scalameta/scalameta/blob/master/semanticdb/semanticdb3/semanticdb3.md
[index-while-building]: https://youtu.be/jGJhnIT-D2M
[code outline]: https://marketplace.visualstudio.com/items?itemName=patrys.vscode-code-outline
[atom-ide-ui]: https://atom.io/packages/atom-ide-ui
[bsp]: https://github.com/scalacenter/bsp/blob/master/docs/bsp.md
[scalafmt]: http://scalafmt.org/
[scalafix]: https://scalacenter.github.io/scalafix/
