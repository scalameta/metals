---
id: vscode
sidebar_label: VS Code
title: Visual Studio Code
---

![Goto Definition](https://user-images.githubusercontent.com/1408093/48776422-1f764f00-ecd0-11e8-96d1-170f2354d50e.gif)

```scala mdoc:requirements

```

## Installation

Install the Metals extension from the
[Marketplace](https://marketplace.visualstudio.com/items?itemName=scalameta.metals) by clicking on this badge [![Install Metals extension](https://img.shields.io/badge/metals-vscode-blue.png)](vscode:extension/scalameta.metals) or via the VS Code editor:

![install stable version](https://imgur.com/Qew0fNH.png)

> Make sure to disable the extensions
> [Scala Language Server](https://marketplace.visualstudio.com/items?itemName=dragos.scala-lsp)
> and
> [Scala (sbt)](https://marketplace.visualstudio.com/items?itemName=lightbend.vscode-sbt-scala)
> if they are installed. The
> [Dotty Language Server](https://marketplace.visualstudio.com/items?itemName=lampepfl.dotty)
> does **not** need to be disabled because the Metals and Dotty extensions don't
> conflict with each other. However, if you want to work on Scala 3 code in a
> workspace that was previously opened with `Dotty Language Server` you need to
> first remove `.dotty-ide-artifact` before opening the workspace with Metals.

Next, open a directory containing your Scala code. The extension activates when
the main directory contains `build.sbt` or `build.sc` file, a Scala file is
opened, which includes `*.sbt`, `*.scala` and `*.sc` file, or a standard Scala
directory structure `src/main/scala` is detected.

It is also possible to opt in to install the pre-release version and try out the latest cutting edge features from Metals server. 
Apart from new features, pre-release versions also include many bugfixes. It's encouraged to use them with [SNAPSHOT](#SNAPSHOT) releases of Metals server. Using pre-release versions may result in less stable experience and it is not indented for beginners.
Pre-release versions follow `major.minor.PATCH` versioning.

![Install the pre-release extension](https://imgur.com/CzOTleE.png)

```scala mdoc:editor:vscode
Update the "Sbt Script" setting to use a custom `sbt` script instead of the
default Metals launcher if you need further customizations like reading environment
variables.

![Sbt Launcher](https://i.imgur.com/NuwEBe4.png)
```

```scala mdoc:command-palette:vscode

```

## Configure Java version

The VS Code plugin uses by default the `JAVA_HOME` environment variable (via
[`locate-java-home`](https://www.npmjs.com/package/locate-java-home)) to locate
the `java` executable. To override the default Java home location, update the
"Java Home" variable in the settings menu.

![Java Home setting](https://i.imgur.com/sKrPKk2.png)

If this setting is defined, the VS Code plugin uses the custom path instead of
the `JAVA_HOME` environment variable.

### macOS

To globally configure `$JAVA_HOME` for all GUI applications, see
[this Stackoverflow answer](https://stackoverflow.com/questions/135688/setting-environment-variables-on-os-x).

If you prefer to manually configure Java home through VS Code, run the following
command to copy the Java 8 home path.

```sh
/usr/libexec/java_home -v 1.8 | pbcopy
```

## Custom artifact repositories (Maven or Ivy resolvers)

Use the 'Custom Repositories' setting for the Metals VS Code extension to tell
[Coursier](https://get-coursier.io/docs/other-proxy) to try to download Metals
artifacts from your private artifact repository.

Use `.jvmopts` to set sbt options
(https://www.scala-sbt.org/1.0/docs/Proxy-Repositories.html) for
`sbt bloopInstall` which resolves library dependencies. You can also provide a
custom sbt script (see 'Custom sbt launcher').

## HTTP proxy

Metals uses [Coursier](https://get-coursier.io/docs/other-proxy) to download
artifacts from Maven Central. To use Metals behind an HTTP proxy, configure the
system properties `-Dhttps.proxyHost=… -Dhttps.proxyPort=…` in one of the
following locations:

- `.jvmopts` file in the workspace directory.
- `JAVA_OPTS` environment variable, make sure to start `code` from your terminal
  when using this option since environment variables don't always propagate
  correctly when opening VS Code as a GUI application outside a terminal.
- "Server Properties" setting for the Metals VS Code extension, which can be
  configured per-workspace or per-user.

##  Using latest Metals <a name="SNAPSHOT">SNAPSHOT</a>

Update the "Server Version" setting to try out the latest pending Metals
features.

```scala mdoc:releases

```

Run the "Reload Window" command after updating the setting for the new version
to take effect.

```scala mdoc:generic

```

## Show document symbols

Run the "Explorer: Focus on Outline View" command to open the symbol outline for
the current file in the sidebar.

![Document Symbols Outline](https://i.imgur.com/T0kVJsr.gif)

Run the "Open Symbol in File" command to search for a symbol in the current file
without opening the sidebar.

![Document Symbols Command](https://i.imgur.com/0PJ4brd.png)

As you type, the symbol outline is also visible at the top of the file.
![Document Symbols Outline](https://i.imgur.com/L217n4q.png)

```scala mdoc:parent-lenses:vscode

```

```scala mdoc:new-project:vscode

```

## Running and debugging your code

Metals supports running and debugging tests and main methods via the
[Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/).
The protocol is used to communicate between the editor and debugger, which means
that applications can be run the same as for any other language in the natively
supported `Run` view. When using Metals the debugger itself is
[Bloop](https://scalacenter.github.io/bloop/), which is also responsible for
starting the actual process.

Users can begin the debugging session in two ways:

### via code lenses

![lenses](https://i.imgur.com/5nTnrcS.png)

For each main or test class Metals shows two code lenses `run | debug` or
`test | test debug`, which show up above the definition as a kind of virtual
text. Clicking `run` or `test` will start running the main class or test without
stopping at any breakpoints, while clicking `debug` or `test debug` will pause
once any of them are hit. It's not possible to add any arguments or java
properties when running using this method.

### via a `launch.json` configuration

Visual Studio Code uses `.vscode/launch.json` to store user defined
configurations, which can be run using:

- The `Run -> Start Debugging` menu item or `workbench.action.debug.start`
  shortcut.
- The `Run -> Run Without Debugging` menu item or `workbench.action.debug.run`
  shortcut.

If a user doesn't have anything yet saved, a configuration wizard will pop up to
guide them. In the end users should end up with something like this:

```json
{
  "version": "0.2.0",
  "configurations": [
    // Main class configuration
    {
      "type": "scala",
      "request": "launch",
      // configuration name visible for the user
      "name": "Launch Main",
      // full name of the class to run
      "mainClass": "com.example.Main",
      // optional arguments for the main class
      "args": [],
      // optional jvm properties to use
      "jvmOptions": []
    },
    // Test class configuration
    {
      "type": "scala",
      "request": "launch",
      // configuration name visible for the user
      "name": "Launch Test",
      // full name of the class to run
      "testClass": "com.example.Test"
    },
    // Attach debugger when running via:
    // `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005`
    {
      "type": "scala",
      "request": "attach",
      "name": "Attach debugger",
      // name of the module that is being debugging
      "buildTarget": "root",
      // Host of the jvm to connect to
      "hostName": "localhost",
      // Port to connect to
      "port": 5005
    }
  ]
}
```

You can also add an optional build target name, which is needed in case there
are more than one class with the same name or when launching a class from
outside the project. Inside `"configurations":` add the key `buildTarget` with
your target name, e.g. `root`:

```json
      "buildTarget": "root"
```

The build target name corresponds to your project name. For example in sbt for
`lazy val interfaces = project` the name of the build target will be
`interfaces` for sources and `interfaces-test` for tests. To make sure you have
the correct target names please run the command `Metals: Run Doctor`.

Multiple configurations can be stored in that file and can be chosen either
manually in the `Run` view or can be picked by invoking a shortcut defined under
`workbench.action.debug.selectandstart`.

### via Metals' commands

You can also use commands that can be easily bound to shortcuts:

- `metals.run-current-file` - Run main class in the current file.
- `metals.test-current-file` - Run test class in the current file
- `metals.test-current-target` - Run all tests in the current project.

To assign shortcuts just go to the Keyboard Shortcuts page (`File` ->
`Preferences` -> `Keyboard Shortcuts`) and search for a command, click on it and
use your preferred shortcut.

## On type formatting for multiline string formatting

![on-type](https://imgur.com/a0O2vCs.gif)

To properly support adding `|` in multiline strings we are using the
`onTypeFormatting` method. The functionality is enabled by default, but you can
disable/enable `onTypeFormatting` inside Visual Studio Code settings by checking
`Editor: Format On Type`:

![on-type-setting](https://i.imgur.com/s6nT9rC.png)

## Formatting on paste for multiline strings

Whenever text is paste into a multiline string with `|` it will be properly
formatted by Metals:

![format-on-paste](https://i.imgur.com/fF0XWYC.gif)

This feature is enabled by default. If you need to disable/enable formatting on
paste in Visual Studio Code you can check the `Editor: Format On Paste` setting:

![format-on-paste-setting](https://i.imgur.com/rMrk27F.png)

```scala mdoc:worksheet:vscode

```

```scala mdoc:scalafix:vscode

```

## Searching a symbol in the workspace
Metals provides an alternative command to the native "Go to symbol in workspace..." command, in order to work around some VS Code limitations (see [this issue](https://github.com/microsoft/vscode/issues/98125) for more context) and provide richer search capabilities.

You can invoke this command from the command palette (look for "Metals: Search symbol in workspace").
Optionally you can also bind this command to a shortcut. For example, if you want to replace the native command with the Metals one you can configure this shortcut:

```js
  {
    "key": "ctrl+t", // or "cmd+t" if you're on macOS
    "command": "metals.symbol-search",
    "when": "editorLangId == scala"
  }
```

## Test Explorer
Metals 0.11.0 implements Visual Studio Code's [Testing API](https://code.visualstudio.com/api/extension-guides/testing).  

Test Explorer UI is a new default way to run/debug test suites and replaces Code
Lenses. The new UI adds a testing view, which shows all test suites declared in
project's modules. From this panel it's possible to
- view all discovered test suites grouped by build targets (modules) and filter them
- run/debug test
- navigate to test's definition.

![test-explorer](https://i.imgur.com/Z3VtS0O.gif)

If you encounter an error, create an [issue](https://github.com/scalameta/metals/issues).

## Coming from IntelliJ

Install the
[IntelliJ IDEA Keybindings](https://marketplace.visualstudio.com/items?itemName=k--kato.intellij-idea-keybindings)
extension to use default IntelliJ shortcuts with VS Code.

| IntelliJ         | VS Code                   |
| ---------------- | ------------------------- |
| Go to class      | Go to symbol in workspace |
| Parameter info   | Trigger parameter hints   |
| Basic completion | Trigger suggest           |
| Type info        | Show hover                |
| Expand           | Fold                      |
| Extend Selection | Expand selection          |

## GitHub Codespaces and GitHub.dev support

See https://scalameta.org/metals/docs/editors/online-ides#github-codespaces-and-githubdev
