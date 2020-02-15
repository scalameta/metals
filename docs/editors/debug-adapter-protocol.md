---
id: debug-adapter-protocol
sidebar_label: Debug Adapter Protocol
title: Debug Adapter Protocol
---

Metals implements the Debug Adapter Protocol, which can be used by the editor to
communicate with JVM to run and debug code.

## How to add support for debugging in my editor?

The editor needs to handle two commands in its language client extension:
[`metals-run-session-start`](https://github.com/scalameta/metals/blob/master/metals/src/main/scala/scala/meta/internal/metals/ClientCommands.scala#L56)
and
[`metals-debug-session-start`](https://github.com/scalameta/metals/blob/master/metals/src/main/scala/scala/meta/internal/metals/ClientCommands.scala#L78).
Those commands should get executed automatically by the lsp client once the user
activates a code lens. The difference between them is that the former ignores
all breakpoints being set while the latter respects them. The procedure of
starting the run/debug session is as follows:

1. Request the debug adapter uri from metals server using the
   [`debug-adapter-start`](https://github.com/scalameta/metals/blob/master/metals/src/main/scala/scala/meta/internal/metals/ServerCommands.scala#L95)
   command.
2. Connect the debug adapter extension specific to you editor using the
   aforementioned uri and let it drive the run/debug session. For reference,
   take a look at the
   [vscode implementation](https://github.com/scalameta/metals-vscode/blob/master/src/scalaDebugger.ts)
   and how it is
   [wired up together](https://github.com/scalameta/metals-vscode/blob/master/src/extension.ts#L356)


Create the following trace files to spy on incoming/outgoing JSON communication between the debug server and editor.

```
# macOS
touch ~/Library/Caches/org.scalameta.metals/dap-server.trace.json
touch ~/Library/Caches/org.scalameta.metals/dap-client.trace.json
# Linux
touch ~/.cache/metals/dap-server.trace.json
touch ~/.cache/metals/dap-client.trace.json
```
