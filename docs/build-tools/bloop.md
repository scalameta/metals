---
id: bloop
title: Bloop
---

[Bloop](https://scalacenter.github.io/bloop) is a compile server for Scala that
works with sbt and has support for other build tools like Maven, Gradle, Mill,
Fury and Seed. If your workspace contains a `.bloop/` directory with Bloop JSON
files then Metals will automatically connect to it.

To manually tell Metals to connect with Bloop, run the "Connect to build server"
(id: `build.connect`) command. In VS Code, open the "Command palette"
(`Cmd + Shift + P`) and search "connect to build server".

![Import connect to build server command](https://github.com/scalameta/gh-pages-images/metals/bloop/mIR0WTe.png?raw=true)

In case of any issues, it's also possible to restart a running Bloop server
using the `Restart Bloop server` command (id: `build-restart`).

## Installing Bloop CLI

To compile, test and run from your terminal install the `bloop` command-line
interface with the instructions here: https://scalacenter.github.io/bloop/setup

```scala mdoc:custom-bloop

```
