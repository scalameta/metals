---
id: getting-started
title: Contributing to Metals
---

Whenever you are stuck or unsure, please open an issue or [ask us on
Discord](https://discord.gg/DwTc8xbNDd). This project
follows [Scalameta's contribution
guidelines](https://github.com/scalameta/scalameta/blob/master/CONTRIBUTING.md)
and the [Scala CoC](https://scala-lang.org/conduct/).

## Requirements

You will need the following applications installed:

- Java 17
- `git`
- `sbt` (for building a local version of the server)

## Project structure

### Main Metals Project
The main Metals module is located in the `metals` directory.

#### Entrypoint to the Metals Server
The first LSP request (`initialize`) is handled by `MetalsLanguageServer`, which then instantiates `WorkspaceLspService` to manage all subsequent LSP requests. Acting as a dispatcher, `WorkspaceLspService` handles multiple projects within a single workspace by creating a separate `ProjectMetalsLspService` for each one and routing LSP queries accordingly. In addition to these project-specific services, there's a fallback service — `FallbackMetalsLspService` — responsible for handling standalone files. Shared logic between `ProjectMetalsLspService` and `FallbackMetalsLspService` is encapsulated in the `MetalsLspService` class.

### Presentation Compiler
Many of Metals features (e.g., go to references) work primarily using [Semantic DB](https://scalameta.org/docs/semanticdb/guide.html) -- semantic information produced during compilation. However, for actions, that require very up-to-date information, Metals uses presentation compiler (pc). Presentation compiler uses Scala (interactive) compiler, so it is published for a specific Scala version. Presentation compiler for Scala 2 is in the the cross-published `mtags` module in Metals, for Scala 3 in the `scala3-presentation-compiler` module in the `scala/scala3` repository. 

Metals loads a presentation compiler instance for a module (build target) using the required Scala version. The interfaces for communication with presentation compiler are in `mtags-interfaces`, where `PresentationCompiler.java` is the presentation compiler API.

Additionally, Metals has a limited implementation of a presentation compiler for Java in `mtags-java`.

To avoid repetition, common utilities of presentation compilers are in `mtags-shared`. Since Scala 3 compiler cannot have dependencies on Scala projects, `mtags-shared` sources are currently automatically copied to the compiler repository.

### Tests
- `tests/unit` - moderately fast-running unit tests. Mostly contain LSP test, that use Bloop with a quick setup.
- `tests/cross` - tests targeting cross builds for Scala 2 presentation compiler. Analogical tests for Scala 3 are in the compiler repository.
- `tests/slow` - slow integration tests. Contain tests for different build tools and build servers. Mtags are cross published for slow tests, so LSP test for testing non-default Scala 2 versions should also go here.
- `tests/input` - example Scala code that is used as testing data for unit tests.

### Other modules
- `sbt-metals` - the sbt plugin used when users are using the BSP support from
  sbt to ensure semanticDB is being produced by sbt.
- `docs` - documentation markdown for the Metals website.
- `metals-docs` - methods used for generating documentation across multiple pages
  in `docs`.
- `website` - holds the static site configuration, style and blogs posts for the
  Metals website.

Below diagram shows project structure and dependencies among modules. Note that
`default-<suffix>` is a [default root project](https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Default+root+project)
created implicitly by sbt.
![Projects diagram](https://imgur.com/oIhXd5l.png)

## Related projects

The improvement you are looking to contribute may belong in a separate
repository:

- [scalameta/metals-vscode](https://github.com/scalameta/metals-vscode/): the
  Visual Studio Code extension for Metals.
- [scalameta/nvim-metals](https://github.com/scalameta/nvim-metals/): the Neovim
  extension for Metals using the built-in LSP support of Neovim.
- [scalameta/scalameta](https://github.com/scalameta/scalameta/): SemanticDB for Scala 2,
  parsing, tokenization.
- [scalameta/munit](https://github.com/scalameta/munit/): Test framework used in
  the main Metals repository
- [scalacenter/bloop](https://github.com/scalacenter/bloop/): build server for
  compilation.
- [scala/scala](https://github.com/scala/scala/): Scala 2 interactive compiler.
- [scala/scala3](https://github.com/lampepfl/dotty): Scala 3 presentation
  compiler, SemanticDB for Scala 3.
- [scalameta/scalafmt](https://github.com/scalameta/scalafmt/): code formatting.
- [scalacenter/scalafix](https://github.com/scalacenter/scalafix/): code
  refactoring and linting.

## Common development workflow

Most of the time development in Metals looks like:

- do some changes
- write tests which check if your changes work
- publish Metals server locally and test changes manually

When diving into part of the code without any prior knowledge it might be hard
to comprehend what's going on and what part of the code is responsible for
specific behavior. There are several ways to debug Metals, but most popular are:

- debugging through logging (recommended option)
- classic debugging with breakpoints

## Commit messages

Try to follow the
[conventional commits specification](https://www.conventionalcommits.org/en/v1.0.0/),
which means your commits should be of form:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

It's important to over-communicate in your commit messages. Be thorough. It's
important to ensure you outline the problem you're trying to solve, or the
feature you're introducing, and then explain the fix or implementation. This will
help the reviewers with their review and also future contributors to get the
full context of your changes.

For example, this message can be take a form of:

```
Previously, this bug was happening due to invalid handling of URIs. Now, we handle them correctly using a dedicated class.
```

Ideally, each PR would only have one commit and the title with body would be the
same for the pull requests. However, sometimes a PR might require more changes
and commits, in that case please try to keep them self contained, which means
each change pertains to a specific bug or feature and can be reverted
separately.

### Debugging through logging

This approach provides very quick iterations and short feedback loop.
It depends on placing multiple `pprint.log()` calls which will log
messages in `.metals.log` file. Logged output can be watched by `tail -f .metals/metals.log`.

```
MetalsLanguageServer.scala:1841 params: DebugSessionParams [
  targets = SingletonList (
    BuildTargetIdentifier [
      uri = "file:/HappyMetalsUser/metals/#metals/Compile"
    ]
  )
  dataKind = "scala-attach-remote"
  data = {}
]
```

See [workspace logs](#workspace-logs) for more information.

This approach can be used in 2 variants:

- together with [manual testing](#manual-tests) when it's hard to write test
  for some changes.
- with [unit tests](#unit-tests)

### Classic debugging

Classic debugging is possible by the [JVM debugging mechanism](#jvm-debugging).
Publish Metals locally, open a new project and configure debug settings.
Then you can attach IDE with opened Metals repository to the debugged instance:

- VSCode - add attach configuration to yours [launch.json](../editors/vscode#via-a-launchjson-configuration)

  ```
  {
    "version": "0.2.0",
    "configurations": [
      // Attach debugger when running via:
      // `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005`
      {
        "type": "scala",
        "request": "attach",
        "name": "Attach debugger to Metals server",
        "buildTarget": "metals",
        "hostName": "localhost",
        "port": 5005
      }
    ]
  }
  ```

  Then pick such a defined configuration and run debug.

  ![Attach debugger](https://imgur.com/ySAo6Um.png)

- IntelliJ - Select Attach to the process and pick proper process from the list

  ![Attach to the process](https://imgur.com/lHSl57l.png)

## Unit tests

To run the unit tests open an sbt shell and run `unit/test`. However, this command will
run all of the unit tests declared in `unit` module.

```sh
sbt

# (recommended) run a specific test suite, great for edit/test/debug workflows.
> unit/testOnly tests.DefinitionSuite

# run a specific test case inside the suite.
> unit/testOnly tests.DefinitionSuite -- <exact-test-name>

# run unit tests, moderately fast but still a bit too slow for edit/test/debug workflows.
> unit/test

# run slow integration tests, takes several minutes.
> slow/test

# (not recommended) run all tests, slow. It's better to target individual projects.
> test
```

### Manually testing an `LspSuite`

Every test suite that extends `LspSuite` generates a workspace directory under
`tests/unit/target/e2e/<suitename>/<testname>`. To debug why a `LspSuite` might be
failing, run the test once and then open it directly in the editor. For
example, for the test case `"deprecated-scala"` in `WarningsLspSuite` run the
following command:

```
code tests/unit/target/e2e/warnings/deprecated-scala
```

This will open Visual Studio Code in directory with test project and it'll be
possible to investigate why test is failing manually.

## Cross tests

Tests for Scala 2 presenatation compiler, check common features such as hover, completions or signatures.

```sh
sbt

# run presentation compiler tests, these are the quickest tests to run.
> cross/test

# run presentation compiler tests for all Scala versions.
> +cross/test
```

## Manual tests

Some functionality is best to manually test through an editor. A common workflow
while iterating on a new feature is to run `publishLocal` and then open an
editor in a small demo build.

It's important to note that `sbt publishLocal` will create artifacts only for
the Scala version currently used in Metals and trying to use the snapshot
version with any other Scala version will not work. This may be fine if you're
working on a generic feature that isn't using the presentation compiler
(anything in mtags),  if not then you need to publish the specific version of
mtags that you're trying to test

```sh
sbt

> publishLocal

> ++2.12.20 mtags/publishLocal
```

You can also do a full cross publish with `sbt +publishLocal`, however this will
take quite some time, so it's often better to only target the version you need.


### Visual Studio Code

Install the Metals extension from the Marketplace by searching for "Metals".

[Click here to install the Metals VS Code plugin](vscode:extension/scalameta.metals)

Next, update the "Server version" setting under preferences to point to the
version you published locally via `sbt publishLocal`. You'll notice that version has the format
`<version>-SNAPSHOT`.

![Metals server version setting](https://i.imgur.com/ogVWI1t.png)

When you make changes in the Metals Scala codebase

- publish metals binary as described above.
- execute the "Metals: Restart server" command in Visual Studio Code (via
  command palette)

### Vim/Neovim

If using `nvim-metals`:

You'll want to make sure to read the docs
[here](https://github.com/scalameta/nvim-metals/blob/main/doc/metals.txt) and
take a look at the example configuration
[here](https://github.com/scalameta/nvim-metals/discussions/39) if you haven't
already set everything up.

- publish the metals binary as described above.
- set the `serverVersion` in your `settings` table that you pass in to your
  metals config.
- Open your workspace and trigger a `:MetalsUpdate` followed by a
  `:MetalsRestart`. NOTE: that every time you publish locally you'll want to
  trigger this again.

If you are using another Vim client, write a `new-metals-vim` script that builds
a new `metals-vim` bootstrap script using the locally published version.

```sh
coursier bootstrap \
  --java-opt -Dmetals.client=<<NAME_OF_CLIENT>> \
  org.scalameta:metals_2.13:@LOCAL_VERSION@ \ # double-check version here
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

## Workspace logs

Metals logs workspace-specific information to the
`$WORKSPACE/.metals/metals.log` file.

```sh
tail -f .metals/metals.log
```

These logs contain information that may be relevant for regular users.

## JSON-RPC trace

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

## JVM Debugging

To debug the JVM with the Metals server, add a property to your
`Server Properties` with the usual Java debugging flags, making sure you have
the `quiet` option on. It's important to remember about the flag, as the server
uses standard input/output to communicate with the client, and the default
output of the debugger interferes with that.

This property will make your server run in debug mode on port 5005 without
waiting for the debugger to connect:

```sh
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005,quiet=y
```

## Updating build tool launcher/wrappers

Metals uses various wrappers or launchers for each build tool that it supports.
This makes sure that when your in a workspace for you build tool that metals is
able to correctly launch that build tool, even if it doesn't exist on the users
`$PATH`. You can see their usages in `<BuildToolName>BuildTool.scala`.

### Updating sbt-launcher

The easiest way to update the sbt-launcher is with the following coursier
command:

```sh
cp "$(cs fetch org.scala-sbt:sbt-launch:<version>)" sbt-launch.jar
```

This will allow you to not have to do some of the manual steps with the launcher
properties file listed [here](https://github.com/sbt/launcher).

### Updating maven wrappers

For Maven we use the [Maven
Wrapper](https://maven.apache.org/wrapper/maven-wrapper/index.html). In order to
update this you'll want to do the following:

  - Run the `./bin/update-maven-wrapper.sh` script
  - Update the `def version` in `MavenBuildTool.scala` to the latest version
      that you just updated to.
  - Run the specific maven tests and ensure they pass: `./bin/test.sh
      'slow/testOnly -- tests.maven.*'`


## Git hooks

This git repository has a pre-push hook to run Scalafmt.

The CI also uses Scalafix to assert that there a no unused imports. To
automatically remove unused imports run `sbt scalafixAll`. We don't run Scalafix
as a pre-push git hook since starting sbt takes a long time.
