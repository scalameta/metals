---
id: overview
title: Build Tools Overview
sidebar_label: Overview
---

Metals works with the following text editors with varying degree of
functionality.

| Build tool | Installation | Goto library dependencies |
| ---------- | :----------: | :-----------------------: |
| sbt        |  Automatic   |            ✅             |
| Bloop      |  Automatic   |  If configured correctly  |
| Maven      |    Manual    |                           |
| Gradle     |    Manual    |                           |
| Mill       |    Manual    |                           |

## Installation

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

**✅**: it is possible to navigate Scala+Java library dependencies using "Goto
definition".

**If configured correctly**: navigation in library dependency sources works as
long as the
[Bloop JSON files](https://scalacenter.github.io/bloop/docs/configuration-format/)
are populated with `*-sources.jar`.

## Integrating a new build tool

Metals works with any build tool that supports the
[Build Server Protocol](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md).
For more information, see the
[guide to integrate new build tools](new-build-tool.html).
