---
id: new-editor
title: Integrating a new editor
---

Metals is a language server implemented in Scala that communicates with a single
client over [JSON-RPC](https://www.jsonrpc.org/specification).

```scala mdoc:requirements

```

## Starting the server

Use [Coursier](https://github.com/coursier/coursier) to obtain the JVM classpath
of Metals:

```sh
coursier bootstrap org.scalameta:metals_2.12:@VERSION@ -o metals -f
```

(optional) It is recommended to enable JVM string de-duplication and provide
generous stack size and memory options.

```sh
coursier bootstrap \
  --java-opt -XX:+UseG1GC \
  --java-opt -XX:+UseStringDeduplication  \
  --java-opt -Xss4m \
  --java-opt -Xms1G \
  --java-opt -Xmx4G \
  org.scalameta:metals_2.12:@VERSION@ -o metals -f
```

See [Metals server properties](#metals-server-properties) for additional system
properties that are supported by the server.

JSON-RPC communication takes place over standard input/output so the Metals
server does not print anything to the console when it starts. Instead, before
establishing a connection with the client, Metals logs notifications to a global
directory:

```sh
# macOS
~/Library/Caches/org.scalameta.metals/global.log
# Linux
$XDG_CACHE_HOME/org.scalameta.metals/global.log
# Linux (alternative)
$HOME/.cache/org.scalameta.metals/global.log
# Windows
{FOLDERID_LocalApplicationData}\.cache\org.scalameta.metals\global.log
```

After establishing a connection with the client, Metals redirects logs to the
`.metals/metals.log` file in the LSP workspace root directory.

Metals supports two kinds of JSON-RPC endpoints:

- [Language Server Protocol](#language-server-protocol): for the main
  functionality of the server, including editor text synchronization and
  semantic features such as goto definition.
- [Metals extensions](#metals-lsp-extensions): for additional functionality that
  is missing in LSP but improves the user experience.

## Metals server properties

The Metals language server is configured through JVM system properties. A system
property is passed to the server like this:

```sh
# with `java` binary
java -Dmetals.statistics=all ...
# with `coursier bootstrap`
coursier bootstrap --java-opt -Dmetals.statistics=all ...
```

The system properties control how Metals handles certain LSP endpoints. For
example, in vim-lsc the `window/logMessage` notification is always displayed in
the UI so `-Dmetals.status-bar=log-message` can be configured to direct
higher-priority messages to the logs.

### `-Dmetals.verbose`

Possible values:

- `off` (default): don't log unnecessary details.
- `on`: emit very detailed logs, should only be used when debugging problems.

### `-Dmetals.file-watcher`

This option is no longer used by Metals.

### `-Dmetals.glob-syntax`

Controls the glob syntax for registering file watchers on absolute directories.
Registration happens via `client/registerCapability` for the
[`workspace/didChangeWatchedFiles`](#workspacedidchangewatchedfiles) method, if
the editor client supports it.

Possible values:

- `uri` (default): URI-encoded file paths, with forward slash `/` for file
  separators regardless of the operating system. Includes `file://` prefix.
- `vscode`: use regular Path.toString for the absolute directory parts (`/` on
  macOS+Linux and `\` on Windows) and forward slashes `/` for relative parts.
  For example, `C:\Users\IEUser\workspace\project/*.{scala,sbt,properties}`.
  This mode is used by the VS Code client.

### `-Dmetals.status-bar`

Possible values:

- `off` (default): the `metals/status` notification is not supported.
- `on`: the `metals/status` notification is supported.
- `log-message`: translate `metals/status` notifications to `window/logMessage`
  notifications. Used by vim-lsc at the moment.

### `-Dmetals.slow-task`

Possible values:

- `off` (default): the `metals/slowTask` request is not supported.
- `on`: the `metals/slowTask` request is fully supported.
- `status-bar`: the `metals/slowTask` request is not supported, but send updates
  about slow tasks via `metals/status`.

### `-Dmetals.execute-client-command`

Possible values:

- `off` (default): the `metals/executeClientCommand` notification is not
  supported. Client commands can still be handled by enabling
  `-Dmetals.http=true`.
- `on`: the `metals/executeClientCommand` notification is supported and all
  [Metals client commands](#metals-client-commands) are handled.

### `-Dmetals.show-message`

Possible values:

- `on` (default): send `window/showMessage` notifications like usual
- `off`: don't send any `window/showMessage` notifications
- `log-message`: send `window/showMessage` notifications as `window/logMessage`
  instead. Useful when editor client responds to `window/showMessage`
  notification with an intrusive alert.

### `-Dmetals.show-message-request`

Possible values:

- `on` (default): send `window/showMessageRequest` requests like usual
- `off`: don't send any `window/showMessageRequest` requests
- `log-message`: send `window/showMessageRequest` requests as
  `window/logMessage` instead.

### `-Dmetals.http`

Possible values:

- `off` (default): don't start a server with the Metals HTTP client.
- `on`: start a server with the [Metals HTTP client] to interact with the server
  through a basic web UI. This option is needed for editor clients like Sublime
  Text that don't support necessary requests such as
  `window/showMessageRequest`.

### `-Dmetals.icons`

Possible values:

- `none` (default): don't display icons in messages.
- `vscode`: use [Octicons](https://octicons.github.com) such as `$(rocket)` for
  status bar messages, as supported by th
  [VS Code status bar](https://code.visualstudio.com/docs/extensionAPI/vscode-api#StatusBarItem).
- `atom`: use HTML-formatted [Octicons](https://octicons.github.com) such as
  `<span class='icon icon-rocket'></span>` for status bar messages, as supported
  by the Atom status bar.
- `unicode`: use unicode emojis like 🚀 for status bar messages.

### `-Dmetals.exit-on-shutdown`

Possible values:

- `off` (default): run `System.exit` only on the `exit` notification, as
  required by the LSP specification.
- `on`: run `System.exit` after the `shutdown` request, going against the LSP
  specification. This option is enabled by default for Sublime Text to prevent
  the Metals process from staying alive after Sublime Text is quit with `Cmd+Q`.
  It's not possible for Sublime Text packages to register a callback when the
  editor is quit. See [LSP#410](https://github.com/tomv564/LSP/issues/410) for
  more details.

### `-Dmetals.bloop-protocol`

Possible values:

- `auto` (default): use local unix domain sockets on macOS/Linux and TPC sockets
  on Windows for communicating with the Bloop build server.
- `tcp`: use TCP sockets for communicating with the Bloop build server.

### `-Dmetals.statistics`

By default, Metals logs only the most relevant metrics like time it takes to run
sbt and import a workspace. The enable further metrics, update this property
with a comma separated list of the following supported values:

- `memory`: print memory usage of the navigation index after build import.
- `definition`: print total time to respond to `textDocument/definition`
  requests.

Set the value to `-Dmetals.statistics=all` to enable all statistics.

### `-Dmetals.h2.auto-server`

Possible values:

- `on` (default): use
  [H2 `AUTO_SERVER=TRUE` mode](http://www.h2database.com/html/features.html#auto_mixed_mode)
  to support multiple concurrent Metals servers in the same workspace. If this
  option is enabled, the Metals H2 database communicate to other concurrently
  running Metals servers via TCP through a free port. In case of failure to
  establish a `AUTO_SERVER=TRUE` connection, Metals falls back to
  `AUTO_SERVER=FALSE`.
- `off`: do not use use `AUTO_SERVER=TRUE`. By disabling this option, it's not
  possible to run concurrent Metals servers in the same workspace directory. For
  example, it's not possible to have both VS Code and Vim installed with Metals
  running in the same directory. In case there are multiple Metals servers
  running in the same workspace directory, Metals falls back to using an
  in-memory database resulting in a degraded user experience.

```scala mdoc:user-config:system-property

```

## Metals user configuration

Users can customize the Metals server through the LSP
`workspace/didChangeConfiguration` notification. Unlike server properties, it is
normal for regular Metals users to configure these options.

User configuration options can optionally be provided via server properties
using the `-Dmetals.` prefix. System properties may be helpful for editor
clients that don't support `workspace/didChangeConfiguration`. In case user
configuration is defined both via system properties and
`workspace/didChangeConfiguration`, then `workspace/didChangeConfiguration`
takes precedence.

```scala mdoc:user-config:lsp-config

```

## Metals server commands

The client can trigger one of the following commands through the
`workspace/executeCommand` request.

```scala mdoc:commands:server

```

## Metals client commands

The Metals server can send one of the following client commands if the client
supports the `metals/executeClientCommand` notification,

```scala mdoc:commands:client

```

## Metals HTTP client

Metals has an optional web interface that can be used to trigger server commands
and respond to server requests. This interface is not intended for regular
users, it exists only to help editor plugin authors integrate with Metals.

![Metals http client](assets/metals-http-client.png)

The server is enabled by passing the `-Dmetals.http=on` system property. The
server runs by default at [`http://localhost:5031`](http://localhost:5031/).
When the port 5031 is taken the next free increment is chosen instead (5032,
5033, ...).

## Metals LSP extensions

Editor clients can opt into receiving Metals-specific JSON-RPC requests and
notifications. Metals extensions are not defined in LSP and are not strictly
required for the Metals server to function but it is recommended to implement
them to improve the user experience.

To enable Metals extensions, start the main process with the system property
`-Dmetals.extensions=true`.

### `metals/slowTask`

The Metals slow task request is sent from the server to the client to notify the
start of a long running process with unknown estimated total time. A
`cancel: true` response from the client cancels the task. A `$/cancelRequest`
request from the server indicates that the task has completed.

![Metals slow task](assets/metals-slow-task.gif)

The difference between `metals/slowTask` and `window/showMessageRequest` is that
`slowTask` is time-sensitive and the interface should display a timer for how
long the task has been running while `showMessageRequest` is static.

_Request_:

- method: `metals/slowTask`
- params: `MetalsSlowTaskParams` defined as follows:

```ts
interface MetalsSlowTaskParams {
  /** The name of this slow task */
  message: string;
}
```

_Response_:

- result: `MetalsSlowTaskResponse` defined as follows

```ts
interface MetalsSlowTaskResult {
  /**
   * If true, cancel the running task.
   * If false, the user dismissed the dialogue but want to
   * continue running the task.
   */
  message: string;
}
```

### `metals/status`

The Metals status notification is sent from the server to the client to notify
about non-critical and non-actionable events that are happening in the server.
Metals status notifications are a complement to `window/showMessage` and
`window/logMessage`. Unlike `window/logMessage`, status notifications should
always be visible in the user interface. Unlike `window/showMessage`, status
notifications are not critical meaning that they should not demand too much
attention from the user.

In general, Metals uses status notifications to update the user about ongoing
events in the server such as batch compilation in the build server or when a
successful connection was established with the build server.

![Metals status bar](assets/metals-status.gif)

The "🚀 Imported build" and "🔄 Compiling explorer" messages at the bottom of
the window are `metals/status` notifications.

_Notification_:

- method: `metals/status`
- params: `MetalsStatusParams` defined as follows:

```ts
interface MetalsStatusParams {
  /** The text to display in the status bar. */
  text: string;
  /** If true, show the status bar. */
  show?: boolean;
  /** If true, hide the status bar. */
  hide?: boolean;
  /** If set, display this message when user hovers over the status bar. */
  tooltip?: string;
  /** If set, execute this command when the user clicks on the status bar item. */
  command?: string;
}
```

### `metals/didFocusTextDocument`

The Metals did focus notification is sent from the client to the server when the
editor changes focus to a new text document. Unlike `textDocument/didOpen`, the
did focus notification is sent even when the text document is already open.

![Metals did focus](assets/metals-did-focus.gif)

Observe that the compilation error appears as soon as `UserTest.scala` is
focused even if the text document was already open before. The LSP
`textDocument/didOpen` notification is only sent the first time a document so it
is not possible for the language server to re-trigger compilation when moves
focus back to `UserTest.scala` that depends on APIs defined in `User.scala`.

_Notification_:

- method: `metals/didFocusTextDocument`
- params: `string`, the URI of the document where the focused was moved to.

### `metals/executeClientCommand`

The Metals execute client command is sent from the server to the client to
trigger an action inside the editor. This notification is a copy of the
`workspace/executeCommand` except

- execute client command is a notification, not a request
- execute client command is initiated from the server, not the client

See [Metals client command] for the list of supported client commands.

_Notification_:

- method: `metals/executeClientCommand`
- params: `ExecuteCommandParams`, as defined in LSP.

## Language Server Protocol

Consult the
[LSP specification](https://microsoft.github.io/language-server-protocol/specification)
to learn more more how LSP works. Metals uses the following endpoints from the
specification.

### `initialize`

- the `rootUri` field is used to configure Metals for that workspace directory.
  The working directory for where server is started has no significant meaning.
- at this point, Metals uses only full text synchronization. In the future, it
  will be able to use incremental text synchronization.
- `didChangeWatchedFiles` client capability is used to determine whether to
  register file watchers.

### `initialized`

Triggers build server initialization and workspace indexing. The `initialized`
notification is critical for any Metals functionality to work.

### `shutdown`

It is very important that the client sends a shutdown request in order for
Metals to clean up open resources.

- persists incremental compilation analysis files. Without a `shutdown` hook,
  Metals will need to re-compile the entire workspace on next startup.
- stops ongoing processes such as `sbt bloopInstall`
- closes database connections

### `exit`

Kills the process using `System.exit`.

### `$/cancelRequest`

Used by `metals/slowTask` to notify when a long-running process has finished.

### `client/registerCapability`

If the client declares the `workspace.didChangeWatchedFiles` capability during
the `initialize` request, then Metals follows up with a
`client/registerCapability` request to register file watchers for certain glob
patterns.

### `textDocument/didOpen`

Triggers compilation in the build server for the build target containing the
opened document. Related, see `metals/didFocusTextDocument`.

### `textDocument/didChange`

Required to know the text contents of the current unsaved buffer.

### `textDocument/didClose`

Cleans up resources.

### `textDocument/didSave`

Triggers compilation in the build server and analyses if the build needs to be
re-imported.

### `textDocument/publishDiagnostics`

Metals forwards diagnostics from the build server to the editor client.
Additionally, Metals publishes `Information` diagnostics for unexpected
compilation errors when navigating external library sources.

### `textDocument/definition`

Metals supports goto definition for workspace sources in addition to external
library sources.

- Library sources live under the directory `.metals/readonly` and they are
  marked as read-only to prevent the user from editing them.
- The destination location can either be a Scala or Java source file. It is
  recommended to have a Java language server installed to navigate Java sources.

### `textDocument/documentSymbol`

Returns `DocumentSymbol[]` if the client declares support for hierarchical
document symbol or `SymbolInformation[]` otherwise.

### `workspace/didChangeWatchedFiles`

Optional. Metals uses a built-in file watcher for critical functionality such as
Goto Definition so it is OK if an editor does not send
`workspace/didChangeWatchedFiles` notifications.

Metals listens to `workspace/didChangeWatchedFiles` notifications from the
editor for nice-to-have but non-critical file watching events. Metals
automatically registers for the following glob patterns if the editor supports
dynamic registration for file watching.

```scala mdoc:file-watcher

```

The editor is responsible for manually watching these file patterns if the
editor does not support dynamic file watching registration but can still send
`workspace/didChangeWatchedFiles` notifications.

### `workspace/executeCommands`

Used to trigger a [Metals server command](#metals-server-commands).

### `workspace/didChangeConfiguration`

Used to update [Metals user configuration](#metals-user-configuration).

### `window/logMessage`

Used to log non-critical and non-actionable information. The user is only
expected to use the logs for troubleshooting or finding metrics for how long
certain events take.

### `window/showMessage`

Used to send critical but non-actionable notifications to the user. For
non-critical notifications, see `metals/status`.

### `window/showMessageRequest`

Used to send critical and actionable notifications to the user. To notify the
user about long running tasks that can be cancelled, the extension
`metals/slowTask` is used instead.
