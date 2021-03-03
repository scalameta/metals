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
[`metals-run-session-start`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/ClientCommands.scala)
and
[`metals-debug-session-start`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/ClientCommands.scala).
These commands should get executed automatically by the LSP client once the user
activates a code lens. The difference between them is that the former ignores
all breakpoints being set while the latter respects them. The procedure of
starting the run/debug session is as follows:

Then we can request the debug adapter URI from the metals server using the
[`debug-adapter-start`](https://github.com/scalameta/metals/blob/master/metals/src/main/scala/scala/meta/internal/metals/ServerCommands.scala)
command.

### Via explicit main or test commands

Apart from using code lenses, users can start a debug session by executing the
`debug-adapter-start` command with any of following params:

- for an explicit main class

```json
{
  "mainClass": "com.foo.App",
  "buildTarget": "foo",
  "args": ["bar"],
  "jvmOptions": ["-Dpropert=123"],
  "env": { "RETRY": "TRUE" },
  "envFile": ".env"
}
```

- for an explicit test class

```json
{
  "testClass": "com.foo.FooSuite",
  "buildTarget": "foo"
}
```

`buildTarget` is an optional parameter, which might be useful if there are
identically named classes in different modules. A URI will be returned that can
be used by the DAP client.

`envFile` is an optional parameter, which allows you to specify a path to a
`.env` file with additional environment variables. The path can be either
absolute or relative to your project workspace. The parser supports single line
as well as multi-line quoted values (without value substitution). Any variables
defined in the `env` object take precedence over those from the `.env` file.
Here's an example of a supported `.env` file:

```bash
# single line values
key1=value 1
key2='value 2'   # ignored inline comment
key3="value 3"

# multi-line values
key4='line 1
line 2'
key5="line 1
line 2"

# export statements
export key6=value 6

# comma delimiter
key7:value 6

# keys cannot contain dots or dashes
a.b.key8=value 8   # will be ignored
a-b-key9=value 9   # will be ignored
```

- for Metals discovery

This option works a bit different than the other two param shapes as you don't
specify a test or main class, but rather a `runType` of either `"run"`,
`"testFile"`, or `"testTarget"` and a file URI representing your current location.
`"run"` will automatically find any main method in the build target that belongs
to the URI that was sent in. If multiple are found, you will be given the choice
of which to run. The `"testFile"` option will check for any test classes in your
current file and run them. Similarly, `"testTarget"` will run all test classes
found in the build target that the URI belongs to. The `"args"`, `"jvmOptions"`,
`"env"`, and `"envFile"` are all valid keys that can be sent as well with the
same format as above.

```json
{
  "path": "file:///path/to/my/file.scala"
  "runType": "testTarget"
}
```

### Wiring it all together

No matter which method you use, you still need to connect the debug adapter
extension specific to you editor using the aforementioned URI and let it drive
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
