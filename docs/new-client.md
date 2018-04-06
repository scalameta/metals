# Writing a new editor client

Before writing a new editor client, first check if someone else has managed to
integrate metals with your favorite text editor.

* [VS Code](https://github.com/scalameta/metals/blob/master/vscode-extension/src/extension.ts),
  maintained in this repo
* [Atom](https://github.com/laughedelic/atom-ide-scala), maintained by
  [@laughedelic](https://github.com/laughedelic)
* [Emacs](https://github.com/rossabaker/lsp-scala), maintained by
  [@rossabaker](https://github.com/rossabaker)
* Others, see [#217](https://github.com/scalameta/metals/issues/217). Please
  open an issue or ask on [gitter](https://gitter.im/scalameta/scalameta) if you
  want to create a new editor client.

To integrate metals with a new editor, a couple of things should be kept in mind

<!-- TOC depthFrom:2 depthTo:2 -->

* [Launching the server](#launching-the-server)
* [Working directory](#working-directory)
* [File watching](#file-watching)
* [Configuration](#configuration)
* [Commands](#commands)
* [Code actions](#code-actions)

<!-- /TOC -->

## Launching the server

The server can be launched with
[coursier](https://github.com/coursier/coursier/)

[ ![Download](https://api.bintray.com/packages/scalameta/maven/metals/images/download.svg) ](https://bintray.com/scalameta/maven/metals/_latestVersion)

```
coursier launch -r bintray:scalameta/maven org.scalameta:metals_2.12:SERVER_VERSION -M scala.meta.metals.Main
```

`SERVER_VERSION` can either be a locally published version of the metals server
via `sbt metals/publishLocal` or an unsupported pre-release published
automatically to Bintray by our Travis CI. There are no stable or officially
supported releases of metals.

The following Java options are recommended:

* `-XX:+UseG1GC -XX:+UseStringDeduplication`: to reduce memory consumption from
  navigation indexes. May not be necessary in the future.

Refer to the coursier documentation for how to build a fat jar or configure java
parameters.

## Working directory

The server needs to be started in the same directory matching the `rootUri`
parameter of the `initialize` request. Goto definition and other
SemanticDB-features will not work if the working directory does not match the
root directory of the build.

NOTE. Discarding `rootUri` is not compliant with LSP, consider contributing to
[#216][] if you are affected by this issue.

## File watching

Metals delegates file watching to the editor client by listening for
[`workspace/didChangeWatchedFiles`][] notifications. The server expects the
client to send notifications for changes to files matching the following
patterns

* `.metals/buildinfo/**/*.properties`: build metadata to enable goto definition
  for the classpath, completions with the presentation compiler and refactoring
  with Scalafix.
* `**/*.semanticdb`: artifacts produced by the semanticdb-scalac compiler plugin
  during batch compilation in the build. See
  [here](https://github.com/scalameta/scalameta/blob/master/semanticdb/semanticdb3/semanticdb3.md)
  to learn more about SemanticDB. These files are required for goto definition,
  find references, hover and Scalafix to work.

See the VS Code plugin
[clientOptions](https://github.com/scalameta/metals/blob/fb166f1d81eb77ebd9c6b3ee95e65fb58a907eec/vscode-extension/src/extension.ts#L44-L54)
for an example of how file watching is handled in VS Code.

The metals server scans the workspace directory for these file patterns on
startup regardless of file watching notifications. This means goto definition
and other functionality may work to begin with, but quickly degrade for longer
running sessions.

NOTE. The metals server does not use `DidChangeWatchedFilesRegistrationOptions`
to register these particular patterns, consider contributing to [#255][] if you
are affected by this issue.

## Configuration

Metals uses
[`workspace/didChangeConfiguration`](https://microsoft.github.io/language-server-protocol/specification#workspace_didChangeWatchedFiles)
notifications to allow end-users to control behavior of the server. The editor
client is expected to send a `workspace/didChangeConfiguration` notification
containing user configuration right after the `initialized` notification.

A full list of supported configuration options can be found in the VS Code
plugin
[package.json](https://github.com/scalameta/metals/blob/master/vscode-extension/package.json)
manifest. Some configuration options are required by the server while others
should be handled by the client.

Server side configuration options include settings to enable
experimental/unstable features such as completions with the presentation
compiler. Observe that even if metals registers the capability for completions
during the `initialize` handshake, the end-user must opt-into enabling
completions with the configuration setting `scalac.completions.enabled=true`. If
completions are disabled in the configuration
(`scalac.completions.enabled=false`, default value), then metals responds with
an empty list of completion suggestions.

Client side configuration options include

* `serverVersion: String`: while metals is still under active development, it is
  recommended to allow end-users to easily configure the version of the metals
  server.
* `scalafmt.onSave: Boolean`: if true, runs scalafmt on
  [`textDocument/willSaveWaitUntil`][] client requests.

## Commands

Metals uses [`workspace/executeCommand`][] requests to allow end-users to
trigger actions in the server on-demand. The full list of supported commands by
the server can be found in [WorkspaceCommand.scala][]. See the VS Code plugin
[package.json][] manifest how these commands are configured in VS Code. Observe
that `restartServer` must be handled client-side.

## Code actions

The metals server uses [`textDocument/codeAction`][] requests from the editor
client to provide passive refactoring hints with scalafix. Currently, only the
"removed unused import" refactoring is supported but more refactorings may be
added in the future.

<img src="img/code-actions.png" align="right" width="150px" style="padding-left: 20px"/>

The sequence diagram for refactoring hints is quite involved.

1.  Server publishes warning diagnostics about "Unused import"
2.  Client sends a code action request with the "Unused import" warning as part
    of `context.diagnostics`. Server responds with instructions to execute the
    `scalafixUnusedImport` command.
3.  User triggers the `scalafixUnusedImports` command on-demand.
4.  Server sends request to apply a set of text edits. Client responds if edit
    was successfully applied or not.
5.  Server responds to `workspace/executeCommand` request.

In VS Code, code actions are suggested to the user via light bulbs when hovering
above the "Unused import" warnings.

![](img/code-actions.gif)

[`textdocument/willsavewaituntil`]: https://microsoft.github.io/language-server-protocol/specification#textDocument_willSaveWaitUntil
[`textdocument/codeaction`]: https://microsoft.github.io/language-server-protocol/specification#textDocument_codeAction
[`workspace/executecommand`]: https://microsoft.github.io/language-server-protocol/specification#workspace_executeCommand
[workspacecommand.scala]: https://github.com/scalameta/metals/blob/master/metals/src/main/scala/scala/meta/metals/WorkspaceCommand.scala
[package.json]: https://github.com/scalameta/metals/blob/master/vscode-extension/package.json
[`workspace/didchangewatchedfiles`]: https://microsoft.github.io/language-server-protocol/specification#workspace_didChangeWatchedFiles
[#216]: https://github.com/scalameta/metals/issues/216
[#255]: https://github.com/scalameta/metals/issues/255
