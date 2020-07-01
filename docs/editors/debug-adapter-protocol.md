---
id: debug-adapter-protocol
sidebar_label: Debug Adapter Protocol
title: Debug Adapter Protocol
---

Metals implements the Debug Adapter Protocol, which can be used by the editor to
communicate with JVM to run and debug code.

## How to add support for debugging in my editor?

There are two main ways to add support for debugging depending on the
capabilities exposed by the client.

### Via code lenses

The editor needs to handle two commands in its language client extension:
[`metals-run-session-start`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/ClientCommands.scala#L56)
and
[`metals-debug-session-start`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/ClientCommands.scala#L78).
Those commands should get executed automatically by the lsp client once the user
activates a code lens. The difference between them is that the former ignores
all breakpoints being set while the latter respects them. The procedure of
starting the run/debug session is as follows:

Then we can request the debug adapter uri from the metals server using the
[`debug-adapter-start`](https://github.com/scalameta/metals/blob/master/metals/src/main/scala/scala/meta/internal/metals/ServerCommands.scala#L108)
command.

### Via simple commands

Apart from using code lenses, users can start a debug session by executing the
`debug-adapter-start` command with the following params:

- for main class

```json
{
  "mainClass": "com.foo.App",
  "buildTarget": "foo",
  "args": ["bar"]
}
```

- for test class

```json
{
  "testClass": "com.foo.FooSuite",
  "buildTarget": "foo"
}
```

`buildTarget` is an optional parameter, which might be useful if there are
identically named classes in different modules. A uri will be returned that can be
used by the DAP client.

### Wiring it all together

No matter which method you use, you still need to connect the debug adapter
extension specific to you editor using the aforementioned uri and let it drive
the run/debug session. For reference, take a look at the
[vscode implementation](https://github.com/scalameta/metals-vscode/blob/master/src/scalaDebugger.ts)
and how it is
[wired up together](https://github.com/scalameta/metals-vscode/blob/master/src/extension.ts#L356)

## Debugging the connection

Create the following trace files to spy on incoming/outgoing JSON communication
between the debug server and editor.

```
# macOS
touch ~/Library/Caches/org.scalameta.metals/dap-server.trace.json
touch ~/Library/Caches/org.scalameta.metals/dap-client.trace.json
# Linux
touch ~/.cache/metals/dap-server.trace.json
touch ~/.cache/metals/dap-client.trace.json
```
