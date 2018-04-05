# Writing a new editor client

Before writing a new editor client, first check if someone else has managed to
integrate Metals with your favorite text editor.

* [VS Code](https://github.com/scalameta/metals/blob/master/vscode-extension/src/extension.ts),
  maintained in this repo
* [Atom](https://atom.io/packages/ide-scala), maintained by
  [@laughedelic](https://github.com/laughedelic)
* [Emacs](https://github.com/rossabaker/lsp-scala), maintained by
  [@rossabaker](https://github.com/rossabaker)
* Others, see [#217](https://github.com/scalameta/metals/issues/217)

To integrate with Metals with a new editor client, a couple of things should be
kept in mind

<!-- TOC depthFrom:2 depthTo:2 -->

* [Working directory](#working-directory)
* [workspace/didChangeWatchedFiles](#workspacedidchangewatchedfiles)
* [JVM parameters](#jvm-parameters)
* [Launching the server](#launching-the-server)

<!-- /TOC -->

## Working directory

The server binary needs to be started in the same directory matching the
`rootUri` parameter of the `initialize` request. Goto definition and other
SemanticDB-features will not work if the working directory where the metals
server is started does not match the root directory of the build.

NOTE. Discarding `rootUri` is not strictly compliant with the LSP, consider
contributing to [#216](https://github.com/scalameta/metals/issues/216) if you
are affected by this issue.

## workspace/didChangeWatchedFiles

The server depends on file watching notifications for the following file
patterns

* `.metals/buildinfo/**/*.properties`: build metadata to enable goto definition
  for the classpath, completions with the presentation compiler and refactoring
  with Scalafix.
* `**/*.semanticdb`: artifacts produced by the semanticdb-scalac compiler plugin
  during batch compilation in the build. See
  [here](https://github.com/scalameta/scalameta/blob/master/semanticdb/semanticdb3/semanticdb3.md)
  to learn more about SemanticDB. These files are required for goto definition,
  find references, hover and Scalafix to work.

NOTE. The Metals server does not use `DidChangeWatchedFilesRegistrationOptions`
to register these particular patterns, consider contributing to
[#255](https://github.com/scalameta/metals/issues/255) if you are affected by
this issue.

## JVM parameters

The VS Code uses the following Java parameters:

* `-XX:+UseG1GC -XX:+UseStringDeduplication`: to reduce memory consumption from
  navigation indices. May not be necessary in the future.

## Launching the server

The server can be launched with
[coursier](https://github.com/coursier/coursier/)

[ ![Download](https://api.bintray.com/packages/scalameta/maven/metals/images/download.svg) ](https://bintray.com/scalameta/maven/metals/_latestVersion)

```
coursier launch -r bintray:scalameta/maven org.scalameta:metals_2.12:SERVER_VERSION -M scala.meta.metals.Main
```

A fat jar can be built using the --standalone with coursier. Unsupported
pre-releases are published automatically to Bintray by our Travis CI. There are
no stable or officially supported releases of Metals.
