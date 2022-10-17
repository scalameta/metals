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
coursier bootstrap org.scalameta:metals_2.13:@VERSION@ -o metals -f
```

(optional) It's recommended to enable JVM string de-duplication and provide a
generous stack size and memory options.

```sh
coursier bootstrap \
  --java-opt -XX:+UseG1GC \
  --java-opt -XX:+UseStringDeduplication  \
  --java-opt -Xss4m \
  --java-opt -Xms100m \
  org.scalameta:metals_2.13:@VERSION@ -o metals -f
```

See [Metals server properties](#metals-server-properties) for additional system
properties that are supported by the server.

JSON-RPC communication takes place over standard input/output so the Metals
server doesn't print anything to the console when it starts. Instead, before
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

## Configuring the server

Over time the recommended way to configure Metals has shifted from heavily
relying on the [Metals server properties](#metals-server-properties) to being
fully able to be configured via `InitializationOptions` which are exchanged
during the
[`initialize`](https://microsoft.github.io/language-server-protocol/specification#initialize)
process. While Metals will still work being fully configured by server
properties, we strongly recommend that instead you rely on the
`InitializationOptions` which are thoroughly covered below in the
[`initialize`](#initialize) section.

## Language Server Protocol

Consult the
[LSP specification](https://microsoft.github.io/language-server-protocol/specification)
to learn more about how LSP works. Metals uses the following endpoints from the
specification.

### `initialize`

- the `rootUri` field is used to configure Metals for that workspace directory.
  The working directory for where server is started has no significant meaning.
- at this point, Metals uses only full text synchronization. In the future, it
  will be able to use incremental text synchronization.
- `didChangeWatchedFiles` client capability is used to determine whether to
  register file watchers.

#### `InitializationOptions`

During `initialize` we also have the ability to pass in `InitializationOptions`.
This is the primary way to configure Metals. In Metals we have a few different
"providers". Some are LSP extensions, such as `metals/inputBox` which you read
about below, and others used to be server properties that have been migrated to
`InitializationOptions`. M

The currently available settings for `InitializationOptions` are listed below.

```typescript
    interface InitializationOptions: {
      compilerOptions: {
        completionCommand?: string;
        isCompletionItemDetailEnabled?: boolean;
        isCompletionItemDocumentationEnabled?: boolean;
        isCompletionItemResolve?: boolean;
        isHoverDocumentationEnabled?: boolean;
        isSignatureHelpDocumentationEnabled?: boolean;
        overrideDefFormat?: "ascii" | "unicode";
        parameterHintsCommand?: string;
        snippetAutoIndent?: boolean;
      }
      debuggingProvider?: boolean;
      decorationProvider?: boolean;
      inlineDecorationProvider?: boolean;
      didFocusProvider?: boolean;
      doctorProvider?: "json" | "html";
      executeClientCommandProvider?: boolean;
      globSyntax?: "vscode" | "uri";
      icons?: "vscode" | "octicons" | "atom" | "unicode";
      inputBoxProvider?: boolean;
      isVirtualDocumentSupported?: boolean;
      isExitOnShutdown?: boolean;
      isHttpEnabled?: boolean;
      openFilesOnRenameProvider?: boolean;
      quickPickProvider?: boolean;
      renameFileThreshold?: number;
      slowTaskProvider?: boolean;
      statusBarProvider?: "on" | "off" | "log-message" | "show-message";
      treeViewProvider?: boolean;
      testExplorerProvider?: boolean;
      openNewWindowProvider?: boolean;
      copyWorksheetOutputProvider?: boolean;
      commandInHtmlFormat?: "vscode" | "sublime";
      doctorVisibilityProvider?: boolean;
    }
```

You can also always check these in the
[`InitializationOptions.scala`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/InitializationOptions.scala)
file where you'll find all of the options and descriptions. Alternatively you can check out the typescript equivalent - [`MetalsInitializationOptions.ts`](https://github.com/scalameta/metals-languageclient/blob/main/src/interfaces/MetalsInitializationOptions.ts)

##### `compilerOptions.completionCommand`

An optional string value for a command identifier to trigger completion
(`textDocument/signatureHelp`) in the editor.

Possible values:

- `"editor.action.triggerSuggest"`: currently used by Visual Studio Code and
  coc.nvim.
- empty: for all other editors.

##### `compilerOptions.isCompletionItemDetailEnabled`

Boolean value signifying whether or not the `CompletionItem.detail` field should
be populated.

Default value: `true`

##### `compilerOptions.isCompletionItemDocumentationEnabled`

Boolean value signifying whether or not the `CompletionItem.documentation` field
should be populated.

Default value: `true`

##### `compilerOptions.isCompletionItemResolve`

Boolean value signifying whether the client wants Metals to handle the
`completionItem/resolve` request.

[https://microsoft.github.io/language-server-protocol/specification#completionItem_resolve](https://microsoft.github.io/language-server-protocol/specification#completionItem_resolve)

Default value: `true`

##### `compilerOptions.isHoverDocumentationEnabled`

Boolean value signifying whether to include docstrings in a `textDocument/hover`
request.

Default value: `true`

##### `compilerOptions.isSignatureHelpDocumentationEnabled`

Boolean value signifying whether or not the `SignatureHelp.documentation` field
should be populated.

Default value: `true`

##### `compilerOptions.overrideDefFormat`

Whether or not the presentation compiler overrides should show unicode icon or
just be in ascii format.

Possible Values:

- `"ascii"` (default)
- `unicode` show the "🔼" icon in overrides.

##### `compilerOptions.parameterHintsCommand`

An optional string value for a command identifier to trigger parameter hints
(`textDocument/signatureHelp`) in the editor. Metals uses this setting to
populate `CompletionItem.command` for completion items that move the cursor
inside an argument list. For example, when completing `"".stripSu@@` into
`"".stripSuffix(@@)`, Metals will automatically trigger parameter hints if this
setting is provided by the editor.

Possible values:

- `"editor.action.triggerParameterHints"`: Used by Visual Studio Code and
  coc.nvim.
- empty: for all other editors.

##### `compilerOptions.snippetAutoIndent`

Certain editors will automatically insert indentation equal to that of the
reference line that the operation started on. This is relevant in the case of
multiline textEdits such as the "implement all methods" completion.

Possible values:

- `on`: (default): the client automatically adds in the indentation. This is the
  case for VS Code, Sublime, and coc.nvim.
- `off`: the client does not add any indentation when receiving a multi-line
  textEdit

##### `copyWorksheetOutputProvider`

Boolean value signifying whether or not the client supports running
CopyWorksheetOutput server command and copying its results into the local
buffer.

Default value: `false`

##### `debuggingProvider`

Boolean value to signify that the client supports the
[Debug Adapter Protocol](../integrations/debug-adapter-protocol.md).

Default value: `false`

##### `decorationProvider`

Boolean value to signify that the client supports the
[Decoration Protocol](../integrations/decoration-protocol.md).

Default value: `false`

##### `didFocusProvider`

Boolean value to signify that the client supports the
[`metals/didFocusTextDocument`](#metalsdidfocustextdocument) extension.

Default value: `false`

##### `disableColorOutput`

Useful for certain DAP clients that are unable to handle color codes for output.
This will remove the color codes coming from whatever DAP server is currently
being used.

Default value: `false`

##### `doctorProvider`

Format that you'd like Doctor to return information in.

Possible values:

- `html`: (default): Metals will return html that can be rendered directly in
  the browser or web view
- `json`: json representation of the information returned by Doctor. See the
    json format [here](#run-doctor).

##### `executeClientCommandProvider`

Possible values:

- `off` (default): the `metals/executeClientCommand` notification is not
  supported. Client commands can still be handled by enabling
  `-Dmetals.http=on`.
- `on`: the `metals/executeClientCommand` notification is supported and all
  [Metals client commands](#metals-client-commands) are handled.

##### `globSyntax`

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

##### `inlineDecorationProvider`

If the client implements the Metals Decoration Protocol **and** supports
decorations to be shown inline and not only at the end of a line.

Default: `false`

##### `icons`

Possible values:

- `none` (default): don't display icons in messages.
- `vscode`: use [Octicons](https://octicons.github.com) such as `$(rocket)` for
  status bar messages, as supported by th
  [VS Code status bar](https://code.visualstudio.com/docs/extensionAPI/vscode-api#StatusBarItem).
- `unicode`: use unicode emojis like 🚀 for status bar messages.

##### `inputBoxProvider`

Possible values:

- `off` (default): the `metals/inputBox` request is not supported. In this case,
  Metals tries to fall back to `window/showMessageRequest` when possible.
- `on`: the `metals/inputBox` request is fully supported.

##### `isExitOnShutdown`

Possible values:

- `off` (default): run `System.exit` only on the `exit` notification, as
  required by the LSP specification.
- `on`: run `System.exit` after the `shutdown` request, going against the LSP
  specification. This option is enabled by default for Sublime Text to prevent
  the Metals process from staying alive after Sublime Text has quit with
  `Cmd+Q`. It's not possible for Sublime Text packages to register a callback
  when the editor has quit. See
  [LSP#410](https://github.com/tomv564/LSP/issues/410) for more details.

##### `isHttpEnabled`

Possible values:

- `off` (default): don't start a server with the Metals HTTP client.
- `on`: start a server with the [Metals HTTP client] to interact with the server
  through a basic web UI. This option is needed for editor clients that don't
  support necessary requests such as `window/showMessageRequest`.

##### `openFilesOnRenameProvider`

Boolean value to signify whether or not the client opens files when doing a
rename.

Default: `false`

##### `openNewWindowProvider`

Boolean value signifying whether or not the client supports opening up a new
window with the newly created project. Used in conjunction with the New Project
Provider.

Default value: `false`

##### `quickPickProvider`

Boolean value to signify whether or not the client implements the
[`metals/quickPick`](#metalsquickpick) extensions.

Default value: `false`

##### `renameFileThreshold`

The max amount of files that you would like the client to open if the client is
a `openFilesOnRenameProvider`.

Default value: `300`

##### `slowTaskProvider`

Possible values:

- `off` (default): the `metals/slowTask` request is not supported.
- `on`: the `metals/slowTask` request is fully supported.

##### `statusBarProvider`

Possible values:

- `off` (default): the `metals/status` notification is not supported.
- `on`: the `metals/status` notification is supported.
- `log-message`: translate `metals/status` notifications to `window/logMessage`
  notifications.
- `show-message`: translate `metals/status` notifications to
  `window/showMessage` show notifications however the client displays messages
  to the user.

##### `treeViewProvider`

Boolean value signifying whether or not the client supports the
[Tree View Protocol](../integrations/tree-view-protocol.md).

Default value: `false`

##### `testExplorerProvider`

Boolean value to signify whether or not the client implements the Test Explorer.

##### `doctorVisibilityProvider`

Boolean value to signify whether or not the client implements the `"metals/doctorVisibilityDidChange"`.
This JSON notification is used to keep track of doctor state. If client implements this provider then Metals server
will send updates to the doctor view.

#### Experimental Capabilities

All of the features that used to be set with `experimental` can now all be set
via `InitializationOptions`. This is the preferred way to configure Metals.

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

### `textDocument/references`

Metals finds symbol references for workspace sources but not external library
dependencies.

LSP does not support streaming references so when project sources have not been
compiled at the point of a request, Metals returns immediately with potentially
incomplete results and triggers a background cascade compilation to find new
symbol references. If new symbol references are discovered after the background
compilation completes, Metals sends a notification via `metals/status` and
`window/logMessage` asking the user to run "find references" again.

### `textDocument/documentSymbol`

Returns `DocumentSymbol[]` if the client declares support for hierarchical
document symbol or `SymbolInformation[]` otherwise.

### `textDocument/formatting`

Formats the sources with the [Scalafmt](https://scalameta.org/scalafmt/) version
that is declared in `.scalafmt.conf`.

- when `.scalafmt.conf` is missing, Metals sends a `window/showMessageRequest`
  to create the file.
- when `.scalafmt.conf` exists but doesn't declare a `version` setting, Metals
  sends a `metals/inputBox` when supported (with fallback to
  `window/showMessageRequest` when unsupported) to prepend `version=$VERSION` to
  the `.scalafmt.conf` file.
- the first format request is usually slow because Metals needs to download
  Scalafmt artifacts from Maven Central. While the download happens, Metals adds
  a message in the status bar via `metals/status` and detailed download progress
  information is logged to `.metals/metals.log`.

### `textDocument/hover`

Returns `Hover` for specified text document and position - [lsp spec](https://microsoft.github.io/language-server-protocol/specifications/specification-3-17/#textDocument_hover).

Metals also support an extended version of this method that supports hover for selection range.
The extended stucture of request params is the following:

```ts
interface HoverExtParams {
  textDocument: TextDocumentIdentifier;
  /** Either `position` or `range` should be specified */
  position?: Position;
  range?: Range;
}
```

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

## Metals server properties

There are various Metals server properties that can be configured through JVM
system properties. A system property is passed to the server like this:

```sh
# with `java` binary
java -Dmetals.statistics=all ...
# with `coursier bootstrap`
coursier bootstrap --java-opt -Dmetals.statistics=all ...
```

Below are the available server properties:

### `-Dmetals.verbose`

Possible values:

- `off` (default): don't log unnecessary details.
- `on`: emit very detailed logs, should only be used when debugging problems.

### `-Dmetals.statistics`

By default, Metals logs only the most relevant metrics like time it takes to run
sbt and import a workspace. To enable further metrics, update this property
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
- `off`: do not use `AUTO_SERVER=TRUE`. By disabling this option, it's not
  possible to run concurrent Metals servers in the same workspace directory. For
  example, it's not possible to have both VS Code and Vim installed with Metals
  running in the same directory. In case there are multiple Metals servers
  running in the same workspace directory, Metals falls back to using an
  in-memory database resulting in a degraded user experience.

### `-Dmetals.pc.debug`

Possible values:

- `off` (default): do not log verbose debugging information for the presentation
  compiler.
- `on`: log verbose debugging information for the presentation compiler.

### `-Dbloop.sbt.version`

Version number of the sbt-bloop plugin to use for the "Install build" command.
Default value is `-Dbloop.sbt.version=@SBT_BLOOP_VERSION@`.

The below properties are also available as user configuration options. It's
preferable to set these there:

### `-Dmetals.bloop-port`

Port number of the Bloop server to connect to. Should only be used if Bloop
server was set up in a custom way. Default value is `8212`.

Possible values are any allowed port number that the Bloop server is able to run
on.

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

```scala mdoc:user-config:lsp-config-default

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

![Metals http client](https://i.imgur.com/t5RJ3q6.png)

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

### Debug Adapter Protocol

Metals implements and additional protocol for running and debugging inside the
editor, see the
[Debug Adapter Protocol](../integrations/debug-adapter-protocol.md).

### Tree View Protocol

Metals implements several custom JSON-RPC endpoints related to rendering tree
views in the editor client, the
[Tree View Protocol](../integrations/tree-view-protocol.md).

### Decoration Protocol

Metals implements an LSP extension to display non-editable text in the editor,
see the [Decoration Protocol](../integrations/decoration-protocol.md).

### `metals/slowTask`

The Metals slow task request is sent from the server to the client to notify the
start of a long running process with unknown estimated total time. A
`cancel: true` response from the client cancels the task. A `$/cancelRequest`
request from the server indicates that the task has completed.

![Metals slow task](https://i.imgur.com/nsjWHWR.gif)

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
  /**
   * If true, the log output from this task does not need to be displayed to the user.
   *
   * In VS Code, the Metals "Output channel" is not toggled when this flag is true.
   */
  quietLogs?: boolean;
  /** Time that has elapsed since the begging of the task. */
  secondsElapsed?: number;
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
  cancel: boolean;
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

![Metals status bar](https://i.imgur.com/XX9CLRH.gif)

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

![Metals did focus](https://i.imgur.com/XjTtAZK.gif)

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

See [Metals client
commands](https://scalameta.org/metals/docs/integrations/new-editor#metals-client-commands)
for the list of supported client commands.

_Notification_:

- method: `metals/executeClientCommand`
- params: `ExecuteCommandParams`, as defined in LSP.

### `metals/inputBox`

The Metals input box request is sent from the server to the client to let the
user provide a string value for a given prompt. Unlike
`window/showMessageRequest`, the `metals/inputBox` request allows the user to
provide a custom response instead of picking a pre-selected value.

_Request_:

- method: `metals/inputBox`
- params: `MetalsInputBoxParams` defined as follows. Note, matches
  [`InputBoxOptions`](https://code.visualstudio.com/api/references/vscode-api#InputBoxOptions)
  in the Visual Studio Code API:

```ts
export interface MetalsInputBoxParams {
  /**
   * The value to prefill in the input box.
   */
  value?: string;
  /**
   * The text to display underneath the input box.
   */
  prompt?: string;
  /**
   * An optional string to show as place holder in the input box to guide the user what to type.
   */
  placeHolder?: string;
  /**
   * Set to `true` to show a password prompt that will not show the typed value.
   */
  password?: boolean;
  /**
   * Set to `true` to keep the input box open when focus moves to another part of the editor or to another window.
   */
  ignoreFocusOut?: boolean;
}
```

- result: `MetalsInputBoxResult` defined as follows:

```ts
export interface MetalsInputBoxResult {
  value?: string;
  cancelled?: boolean;
}
```

### `metals/quickPick`

The Metals quick pick request is sent from the server to the client to let the
user provide a string value by picking one out of a number of given options. It
is similar to `window/showMessageRequest`, but the `metals/quickPick` request
has richer parameters, that can be used to filter items to pick, like
`description` and `detail`.

_Request_:

- method: `metals/quickPick`
- params: `MetalsQuickInputParams` defined as follows. It partially matches
  [`QuickPickOptions`](https://code.visualstudio.com/api/references/vscode-api#QuickPickOptions)
  in the Visual Studio Code API, but it also contains `items` of
  [`MetalsQuickPickItem`](https://code.visualstudio.com/api/references/vscode-api#QuickPickItem),
  which, in it's turn, partially matches `QuickPickItem`, but these interfaces
  do not contain options for picking many items:

```ts
export interface MetalsQuickPickParams {
  /**
   * An array of items that can be selected from.
   */
  items: MetalsQuickPickItem[];
  /**
   * An optional flag to include the description when filtering the picks.
   */
  matchOnDescription?: boolean;
  /**
   * An optional flag to include the detail when filtering the picks.
   */
  matchOnDetail?: boolean;
  /**
   * An optional string to show as place holder in the input box to guide the user what to pick on.
   */
  placeHolder?: string;
  /**
   * Set to `true` to keep the picker open when focus moves to another part of the editor or to another window.
   */
  ignoreFocusOut?: boolean;
}

export interface MetalsQuickPickItem {
  /**
   * An id for this items that should be return as a result of the picking.
   */
  id: string;
  /**
   * A human readable string which is rendered prominent.
   */
  label: string;
  /**
   * A human readable string which is rendered less prominent.
   */
  description?: string;
  /**
   * A human readable string which is rendered less prominent.
   */
  detail?: string;
  /**
   * Always show this item.
   */
  alwaysShow?: boolean;
}
```

- result: `MetalsQuickPickResult` defined as follows:

```ts
export interface MetalsQuickPickResult {
  itemId?: string;
  cancelled?: boolean;
}
```

### `metals/windowStateDidChange`

The `metals/windowStateDidChange` notification is sent from the client to the
server to indicate whether the editor application window is focused or not. When
the editor window is not focused, Metals tries to avoid triggering expensive
computation in the background such as compilation.

_Notification_:

- method: `metals/windowStateDidChange`
- params: `WindowStateDidChangeParams` defined as follows:

```ts
interface WindowStateDidChangeParams( {
  /** If true, the editor application window is focused. False, otherwise. */
  focused: boolean;
}
```

### `metals/openWindow`

The `metals/openWindow` params are used with the New Scala Project
functionality. After the new project has been created, if the editor has the
ability to open the project in a new window then these params are used with the 
`metals-open-folder` command.

```ts
interface MetalsOpenWindowParams {
  /** Location of the newly created project. */
  uri: string;
  /** Whether or not to open the project in a new window. */
  openNewWindow: boolean;
}
```

### `metals/findTextInDependencyJars`

The `FindTextInDependencyJars` request is sent from the client to the server to perform a search though files in the classpath
including binary and sources jars. In response it returns a standard list of `Location` from the [LSP spec](https://microsoft.github.io/language-server-protocol/specification#location).

In case if this endpoint was called with empty `query.pattern` or empty `options.include` server sends [`metals/inputBox`](https://scalameta.org/metals/docs/integrations/new-editor#metalsinputbox)
request to the client to obtain these values.

_Request_:

- method: `metals/findTextInDependecyJars`
- params: `FindTextInDependencyJarsRequest` defined as follows.

```ts
/**
 * Currenly, only `pattern` field is used for search.
 * See: https://github.com/scalameta/metals/issues/3234
 */
interface TextSearchQuery {
    /**
		 * The text pattern to search for.
		 */
    pattern?: string;
    /**
		 * Whether or not `pattern` should be interpreted as a regular expression.
		 */
    isRegExp?: boolean;
    /**
		 * Whether or not the search should be case-sensitive.
		 */
    isCaseSensitive?: boolean;
    /**
		 * Whether or not to search for whole word matches only.
		 */
    isWordMatch?: boolean;
}

interface FindTextInFilesOptions {
    /** Include file filter. Example: `*.conf` */
    include?: string;
    /** Exclude file filter. Example: `*.conf` */
    exclude?: string;
}

interface FindTextInDependencyJarsRequest(
    options?: FindTextInFilesOptions;
    query: TextSearchQuery
)
```

_Response_:

- result: `Location[]`

### `metals/doctorVisibilityDidChange`

When the client opens the doctor view, it will send in `visible: true` to the
server and then `visible: false` when the doctor view is closed. This will
ensure that the doctor checks aren't calculated on the server side when they
aren't needed to since they won't be seen by the client anyways.

_Notification_:

- method: `metals/doctorVisibilityDidChange`
- params: `DoctorVisibilityDidChangeParams`

```ts
interface DoctorVisibilityDidChangeParams {
  visible: boolean;
}
```
