---
id: getting-started
title: Contributing to Metals
---

Whenever you are stuck or unsure, please open an issue or
[ask us on Discord](https://discord.gg/DwTc8xbNDd). This project follows
[Scalameta's contribution guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md).

## Requirements

You will need the following applications installed:

- Java 11 or 8 - Make sure `JAVA_HOME` points to a Java 11 or 8 installation.
  Metals will need to build and run on _both_.
- `git`
- `sbt` (for building a local version of the server)

## Project structure

- `metals` the main project with sources of the Metals language server.
- `sbt-metals` the sbt plugin used when users are using the BSP support from
  sbt to ensure semanticDB is being produced by sbt.
- `mtags` Scala version specific module used to interact with the Scala
  presentation compiler. It's a dependency of the `metals` project and can
  additionally be used by via `mtags-interfaces` to support multiple Scala
  versions inside the Metals server. It's also used by other projects like
  [Metabrowse](https://github.com/scalameta/metabrowse).
- `mtags-interfaces` - java interfaces for the presentation compiler.
- `tests/cross` - tests targeting cross builds for common features such as
  hover, completions, signatures etc.
- `tests/input` example Scala code that is used as testing data for unit tests.
- `tests/unit` moderately fast-running unit tests.
- `tests/slow` slow integration tests.
- `test-workspace` demo project for manually testing Metals through an editor.
- `docs` documentation markdown for the Metals website.
- `metals-docs` methods used for generating documentation across multiple pages
  in `docs`.
- `website` holds the static site configuraton, style and blogs posts for the
  Metals website.

## Git hooks

This git repository has a pre-push hook to run Scalafmt.

The CI also uses Scalafix to assert that there a no unused imports. To
automatically remove unused imports run `sbt scalafixAll`. We don't run Scalafix
as a pre-push git hook since starting sbt takes a long time.

## Related projects

The improvement you are looking to contribute may belong in a separate
repository:

- [scalameta/metals-vscode](https://github.com/scalameta/metals-vscode/): the
  Visual Studio Code extension for Metals.
- [scalameta/coc-metals](https://github.com/scalameta/coc-metals/): the
  [coc.nvim](https://github.com/neoclide/coc.nvim) Vim/Nvim extension for
  Metals.
- [scalameta/metals-eclipse](https://github.com/scalameta/metals-eclipse/): the
  Eclipse extension for Metals.
- [scalameta/scalameta](https://github.com/scalameta/scalameta/): SemanticDB,
  parsing, tokenization.
- [scalameta/munit](https://github.com/scalameta/munit/): Test framework used in
  the main Metals repository
- [scalacenter/bloop](https://github.com/scalacenter/bloop/): build server for
  compilation.
- [scala/scala](https://github.com/scala/scala/): presentation compiler.
- [scalameta/scalafmt](https://github.com/scalameta/scalafmt/): code formatting.
- [scalacenter/scalafix](https://github.com/scalacenter/scalafix/): code
  refactoring and linting.

## Unit tests

To run the unit tests open an sbt shell and run `unit/test`

```sh
sbt
# (recommended) run a specific test suite, great for edit/test/debug workflows.
> unit/testOnly tests.DefinitionSuite
# run a specific test case inside the suite.
> unit/testOnly tests.DefinitionSuite -- *exact-test-name*
# run unit tests, moderately fast but still a bit too slow for edit/test/debug workflows.
> unit/test
# run slow integration tests, takes several minutes.
> slow/test
# run presentation compiler tests, these are the quickest tests to run.
> cross/test
# run presentation compiler tests for all Scala versions.
> +cross/test
# (not recommended) run all tests, slow. It's better to target individual projects.
> test
```

### Manually testing a `LspSuite`

Every test suite that extends `LspSuite` generates a workspace directory under
`tests/unit/target/e2e/$suitename/$testname`. To debug why a `LspSuite` might be
failing, run the test once and then open it directly in your editor. For
example, for the test case `"deprecated-scala"` in `WarningsLspSuite` run the
following command:

```
code tests/unit/target/e2e/warnings/deprecated-scala
```

If you are using VS Code, make sure to update the "Server Version" setting to
use your locally published version of Metals.

## Manual tests

Some functionality is best to manually test through an editor. A common workflow
while iterating on a new feature is to run `publishLocal` and then open an
editor in a small demo build.

### Visual Studio Code

Install the Metals extension from the Marketplace by searching for "Metals".

[Click here to install the Metals VS Code plugin](vscode:extension/scalameta.metals)

Next, update the "Server version" setting under preferences to point to the
version you published locally via `sbt publishLocal`.

![Metals server version setting](https://i.imgur.com/ogVWI1t.png)

When you make changes in the Metals Scala codebase

- run `sbt publishLocal`
- execute the "Metals: Restart server" command in Visual Studio Code (via
  command palette)

It's important to note that `sbt publishLocal` will create artifacts only for
the Scala version currently used in Metals and trying to use the snapshot
version with any other Scala version will not work. In that case you need to run
a full cross publish with `sbt +publishLocal`.

### Vim

First, follow the [`vim` installation instruction](../editors/vim.md).

If you're using coc-metals:

- run `sbt publishLocal`
- open `:CocConfig` and put your new snapshot version in
  `metals.serverVersion`.
- you will then be prompted to reload, which will restart the server.

If you publish again, you then just need to execute the `metals.restartServer command`.

If you are using another Vim client, write a `new-metals-vim` script that builds
a new `metals-vim` bootstrap script using the locally published version.

```sh
coursier bootstrap \
  --java-opt -Dmetals.client=vim-lsc \
  org.scalameta:metals_2.12:@LOCAL_VERSION@ \ # double-check version here
  -r bintray:scalacenter/releases \
  -o /usr/local/bin/metals-vim -f
```

**NOTE** if you're able to configure your client using initialization options,
then the `client` property is not necessary. You can see all the options
[here](https://scalameta.org/metals/docs/integrations/new-editor#initializationoptions).

Finally, start Vim with the local Metals version

```sh
cd test-workspace # any directory you want to manually test Metals
new-metals-vim && vim build.sbt # remember to have the script in your $PATH
```

When you make changes in the Metals Scala codebase, run `sbt publishLocal`, quit
vim and re-run `new-metals-vim && vim build.sbt`.

### Workspace logs

Metals logs workspace-specific information to the
`$WORKSPACE/.metals/metals.log` file.

```sh
tail -f .metals/metals.log
```

These logs contain information that may be relevant for regular users.

### JSON-RPC trace

To see the trace of incoming/outgoing JSON communication with the text editor 
or build server, create empty files in `$WORKSPACE/.metals/` or your machine cache 
directory. 

However, we do not recommend using your machine cache directory because 
trace files located there are shared between all Metals instances, hence multiple 
servers can override the same file. Using `$WORKSPACE/.metals/` solves this issue and 
also allows user to have more precise control over which metals instances log 
their JSON-RPC communication.

```sh
# Linux and macOS
touch $WORKSPACE/.metals/lsp.trace.json # text editor
touch $WORKSPACE/.metals/bsp.trace.json # build server
touch $WORKSPACE/.metals/dap-server.trace.json # debug adapter
touch $WORKSPACE/.metals/dap-client.trace.json # debug adapter
```

```sh
# Windows
type nul > $WORKSPACE/.metals/lsp.trace.json # text editor
type nul > $WORKSPACE/.metals/bsp.trace.json # build server
type nul > $WORKSPACE/.metals/dap-server.trace.json # debug adapter
type nul > $WORKSPACE/.metals/dap-client.trace.json # debug adapter
```

Next when you start Metals, watch the logs with `tail -f`.

```sh
# Linux and macOS
tail -f $WORKSPACE/.metals/lsp.trace.json
```

The traces are very verbose so it's recommended to delete the files if you are
not interested in debugging the JSON communication.

### JVM Debugging

To debug the JVM with the Metals server, add a property to your
`Server Properties` with the usual Java debugging flags, making sure you have
the `quiet` option on. It's important to remember about the flag, as the server
uses standard input/output to communicate with the client, and the default
output of the debuggee interferes with that.

This property will make your server run in debug mode on port 5005 without
waiting for the debugger to connect:

```sh
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005,quiet=y
```

### Updating sbt-launcher

The easiest way to update the sbt-launcher is with the following coursier
command:

```sh
cp "$(cs fetch org.scala-sbt:sbt-launch:<version>)" sbt-launch.jar
```

This will allow you to not have to do some of the manual steps with the launcher
properties file listed [here](https://github.com/sbt/launcher).
