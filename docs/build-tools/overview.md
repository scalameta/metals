---
id: overview
title: Build Tools Overview
sidebar_label: Overview
---

Metals works with the following build tools with varying degrees of
functionality.

| Build tool |    Installation     | Goto library dependencies | Find references |
| ---------- | :-----------------: | :-----------------------: | :-------------: |
| sbt        | Automatic via Bloop |         Automatic         |    Automatic    |
| Maven      | Automatic via Bloop |         Automatic         | Semi-automatic  |
| Gradle     | Automatic via Bloop |         Automatic         |    Automatic    |
| Mill       | Automatic via Bloop |         Automatic         |    Automatic    |
| Bloop      |      Automatic      |      Semi-automatic       | Semi-automatic  |
| Bazel      |      Automatic      |      Build definition     | Semi-automatic  |

## Installation

**Automatic via Bloop**: you can import the build directly from the language
server without the need for running custom steps in the terminal. The build is
exported to [Bloop](https://scalacenter.github.io/bloop/), a Scala build server
that provides fast incremental compilation.

**Automatic**: you can import the build directly from the language server
without the need for running custom steps in the terminal. To use automatic
installation start the Metals language server in the root directory of your
build.

**Manual**: setting up Metals requires a few manual steps to generate
[Bloop](https://scalacenter.github.io/bloop) JSON files. In addition to normal
Bloop installation, Metals requires that the project sources are compiled with
the
[semanticdb-scalac](https://scalameta.org/docs/semanticdb/guide.html#producing-semanticdb)
compiler plugin and `-Yrangepos` option enabled.

## Goto library dependencies

**Automatic**: it is possible to navigate Scala+Java library dependencies using
"Goto definition".

**Semi-automatic**: navigation in library dependency sources works as long as
the
[Bloop JSON files](https://scalacenter.github.io/bloop/docs/configuration-format/)
are populated with `*-sources.jar`.

**Build definition**: navigation in library dependency sources works as long as
the sources are enabled for library dependencies in the Bazel definition.

## Find references

**Automatic**: it is possible to find all references to a symbol in the project.

**Semi-automatic**: it is possible to 'Find symbol references' as soon the
SemanticDB compiler plugin is manually enabled in the build, check separate
build tool pages for details.

## Integrating a new build tool

Metals works with any build tool that supports the
[Build Server Protocol](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md).
For more information, see the
[guide to integrate new build tools](../integrations/new-build-tool.md).
