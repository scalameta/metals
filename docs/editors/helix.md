---
id: helix
sidebar_label: Helix
title: Helix
---

![helix demo](https://github.com/scalameta/gh-pages-images/blob/master/metals/helix/b0sETIY.gif?raw=true)

```scala mdoc:requirements

```

## Installation

Helix requires `metals` to be on the user's path, it should then be
automatically detected and enabled. Installing metals in this manner is most
readily achieved via [Coursier](https://get-coursier.io/), running
`coursier install metals` should be sufficient. To check that Helix is able to
detect metals after this run `hx --health scala`

## Importing builds

At present Helix does not support the
[LSP features](https://github.com/helix-editor/helix/issues/4699) to have metals
prompt the user to import the build as it does in other editors and therefore
the responsibility falls to the user to import manually whenever the build is
updated.

To manually import a build run the `:lsp-workspace-command` command and then
select `build-import` from the list.

```scala mdoc:generic

```
