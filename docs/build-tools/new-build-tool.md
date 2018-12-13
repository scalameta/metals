---
id: new-build-tool
title: Integrating a new build tool
---

Metals uses the
[Build Server Protocol](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md)
(BSP) to communicate with build tools. Any build tool that implements the
protocol should work with Metals.

There are two options for integrating Metals with a new build tool:

- [via Bloop](#bloop-build-server): emit Bloop JSON configuration files and use
  the Bloop build server. The benefit of this approach is that it is simple to
  implement but has the downside that compilation happens outside of your build
  tool.
- [via custom build server](#custom-build-server): add Build Server Protocol
  support to your build tool. The benefit of this approach is that Metals
  integrates directly with your build tool, reproducing the same build
  environment as your current workflow. The downside of this approach is that it
  most likely requires more effort compared to emitting Bloop JSON files.

## Bloop build server

Consult the
[Bloop configuration format](https://scalacenter.github.io/bloop/docs/configuration-format/)
to learn how to emit Bloop JSON files. Once the JSON files have been generated,
use "[Connect to build server](../editors/new-editor.html#import-build)" server
command in Metals to establish a connection with Bloop.

## Custom build server

Consult the
[BSP specification](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md)
to learn about the protocol in detail.

### Server discovery

Metals automatically connects to a BSP server if the workspace root directory
contains a `.bsp/$name.json` file. For example, a build tool named "Bill" will
have a `.bsp/bill.json` file with the following information.

```json
{
  "name": "Bill",
  "version": "1.0.0",
  "bspVersion": "2.0.0",
  "languages": ["scala"],
  "argv": ["bill", "bsp"]
}
```

When Metals detects a `.bsp/bill.json` file in the workspace:

- it starts a new process `bill bsp`, the command is derived from the `argv`
  field in `bill.json`.
- the working directory of the process is the workspace directory.
- communication between Metals and the build server happens through standard
  input/output.

For a plug-and-play example of a build server, see `Bill.scala` in the Metals
repository. Bill is a basic build server that is used for testing and
demonstration purposes only.

### Library bindings

There are two available libraries to implement a BSP server:

- `ch.epfl.scala:bsp4j`: A Java library built with Java `CompletableFuture` for
  async primitives and GSON for JSON serialization. This module is used by
  Metals and the IntelliJ Scala plugin.
- `ch.epfl.scala:bsp4s`: A Scala library built with Monix `Task`/`Observable`
  for async primitives and Circe for JSON for serialization. This module is used
  by the Bloop build server.

Both libraries are compatible with each other. For example, it's OK to implement
a server with bsp4j and a client in bsp4s.

### Manual tests

You can manually test your build server integration by running Metals with your
editor of choice.

Create the following trace files to spy on incoming/outgoing JSON communication
between Metals and your build tool.

```
# macOS
touch ~/Library/Caches/org.scalameta.metals/bsp.trace.json
# Linux
touch ~/.cache/metals/bsp.trace.json
```

Metals must re-start to pick up the trace file.

### Automated tests

The Metals repository has infrastructure for running end-to-end integration
tests with build servers. To test your build server integration, you can to
clone the repository and write custom tests under
`tests/unit/src/test/scala/tests`.

If there is interest, we can publish a Metals `testkit` module to make it
possible to write tests outside the Metals repository.

### SemanticDB

As a build server, you are responsible for enabling the
[SemanticDB](https://scalameta.org/docs/semanticdb/guide.html) compiler plugin.
The SemanticDB plugin is required for features like Goto Definition to work.
Diagnostics work fine without SemanticDB enabled.

Users get a warning from Metals when the build server does not enable the
SemanticDB compiler plugin. Users have an option to suppress this warning by
selecting a "Don't show again" button.

### BSP endpoints

Metals requires the following BSP endpoints to be implemented by the build
server.

- `workspace/buildTargets`: to list all the build targets in the workspace.
- `buildTarget/scalacOptions`: to know the classpath and compiler options used
  to compile the project sources.
- `buildTarget/sources`: to know what source files map to which build targets.
- `buildTarget/dependencySources`: to support "Goto definition" for external
  libraries.
- `buildTarget/compile`: to trigger compilation in a build target.

Additionally, Metals expects the server to send the following notifications:

- `buildTarget/publishDiagnostics`: To report compile errors and warnings in the
  editor buffer.
