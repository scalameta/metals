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
use "[Connect to build server](../users/import-build.md#bloop)" command in
Metals to establish a connection with Bloop.

## Custom build server

Consult the
[BSP specification](https://github.com/scalacenter/bsp/blob/master/docs/bsp.md)
to learn how to implement a build server. There are two available libraries:

- `ch.epfl.scala:bsp4s`: A Scala library built with Monix `Task`/`Observable`
  for async primitives and Circe for JSON for serialization. This module is used
  by the Bloop build server.
- `ch.epfl.scala:bsp4j`: A Java library built with Java `CompletableFuture` for
  async primitives and GSON for JSON serialization. This module is used by
  Metals and the IntelliJ Scala plugin.

Metals requires the following BSP endpoints to be implemented by the build
server.

### `workspace/buildTargets`

To list all the build targets in the workspace.

### `buildTarget/scalacOptions`

To know the classpath and compiler options used to compile the project sources.

### `buildTarget/sources`

To know what source files map to which build targets.

### `buildTarget/dependencySources`

To support "Goto definition" for external libraries.

### `buildTarget/compile`

To trigger compilation in a build target.
