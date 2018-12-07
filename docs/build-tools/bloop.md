---
id: bloop
title: Bloop
---

[Bloop](https://scalacenter.github.io/bloop) is a compile server for Scala that
works with sbt and has experimental support for other build tools like Maven,
Gradle and Mill. If your workspace contains a `.bloop/` directory with Bloop
JSON files then Metals will automatically connect to it.

To manually tell Metals to connect with Bloop, run the "Connect to build server"
(id: `build.connect`) command. In VS Code, open the the "Command palette"
(`Cmd + Shift + P`) and search "connect to build server".

![Import connect to build server command](assets/vscode-connect-build-server.png)

## Installing Bloop CLI

To compile, test and run from your terminal install the `bloop` command-line
interface with the instructions here: https://scalacenter.github.io/bloop/setup

```scala mdoc:custom-bloop

```
