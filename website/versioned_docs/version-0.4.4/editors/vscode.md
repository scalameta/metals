---
id: version-0.4.4-vscode
title: Visual Studio Code
original_id: vscode
---

![Goto Definition](https://user-images.githubusercontent.com/1408093/48776422-1f764f00-ecd0-11e8-96d1-170f2354d50e.gif)


## Requirements

**Java 8**. Metals does not work with Java 11 yet so make sure the JAVA_HOME
environment variable points to Java 8.

**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
tested on Ubuntu+Windows.

**Scala 2.12 and 2.11**. Metals works only with Scala versions 2.12.8, 2.12.7, 2.12.6, 2.12.5, 2.12.4, 2.11.12, 2.11.11, 2.11.10 and 2.11.9.
Note that 2.10.x and 2.13.0-M5 are not supported.

## Installation

Install the Metals extension from the Marketplace, search for "Metals".

[![Install Metals extension](https://img.shields.io/badge/metals-vscode-blue.png)](vscode:extension/scalameta.metals)

> Make sure to disable the extensions
> [Scala Language Server](https://marketplace.visualstudio.com/items?itemName=dragos.scala-lsp)
> and
> [Scala (sbt)](https://marketplace.visualstudio.com/items?itemName=lightbend.vscode-sbt-scala)
> if they are installed. The
> [Dotty Language Server](https://marketplace.visualstudio.com/items?itemName=lampepfl.dotty)
> does **not** need to be disabled because the Metals and Dotty extensions don't
> conflict with each other.

Next, open a directory containing a `build.sbt` file. The extension activates
when a `*.scala` or `*.sbt` file is opened.


## Importing a build

The first time you open Metals in a new workspace it prompts you to import the build.
Click "Import build" to start the installation step.

![Import build](https://i.imgur.com/0VqZWay.png)

- "Not now" disables this prompt for 2 minutes.
- "Don't show again" disables this prompt forever, use `rm -rf .metals/` to re-enable
  the prompt.
- Behind the scenes, Metals uses [Bloop](https://scalacenter.github.io/bloop/) to
  import sbt builds, but you don't need Bloop installed on your machine to run this step.

Once the import step completes, compilation starts for your open `*.scala`
files.

Once the sources have compiled successfully, you can navigate the codebase with
goto definition.

### Custom sbt launcher

By default, Metals runs an embedded `sbt-launch.jar` launcher that respects `.sbtopts` and `.jvmopts`.
However, the environment variables `SBT_OPTS` and `JAVA_OPTS` are not respected.

Update the "Sbt Script" setting to use a custom `sbt` script instead of the
default Metals launcher if you need further customizations like reading environment
variables.

![Sbt Launcher](https://i.imgur.com/NuwEBe4.png)

### Speeding up import

The "Import build" step can take a long time, especially the first time you
run it in a new build. The exact time depends on the complexity of the build and
if library dependencies need to be downloaded. For example, this step can take
everything from 10 seconds in small cached builds up to 10-15 minutes in large
uncached builds.

Consult the [Bloop documentation](https://scalacenter.github.io/bloop/docs/build-tools/sbt#speeding-up-build-export)
to learn how to speed up build import.

### Importing changes

When you change `build.sbt` or sources under `project/`, you will be prompted to
re-import the build.

![Import sbt changes](https://i.imgur.com/72kdZkL.png)


### Manually trigger build import

To manually trigger a build import, execute the "Import build" command through
the command palette (`Cmd + Shift + P`).

![Import build command](https://i.imgur.com/QHLKt8u.png)

## Run doctor

Execute the "Run Doctor" through the command palette to troubleshoot potential
configuration problems in your workspace.

![Run doctor command](https://i.imgur.com/K02g0UM.png)

## Configure Java version

The VS Code plugin uses by default the `JAVA_HOME` environment variable (via
[`find-java-home`](https://www.npmjs.com/package/find-java-home)) to locate the
`java` executable. Metals only works with Java 8 so this executable cannot point
to another version such as Java 11.

To override the default Java home location, update the "Java Home" variable to
in the settings menu.

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

## Using latest Metals SNAPSHOT

Update the "Server Version" setting to try out the latest pending Metals
features.

<table>
<thead>
<th>Version</th>
<th>Published</th>
</thead>
<tbody>
<tr>
<td>0.4.4</td>
<td>02 Feb 2019 18:09</td>
</tr>
<tr>
<td>0.4.4+15-ac8fa735-SNAPSHOT</td>
<td>14 Feb 2019 16:56</td>
</tr>
</tbody>
</table>

Run the "Reload Window" command after updating the setting for the new version
to take effect.


## Gitignore `.metals/` and `.bloop/`

The Metals server places logs and other files in the `.metals/` directory. The
Bloop compile server places logs and compilation artifacts in the `.bloop`
directory. It's recommended to ignore these directories from version control
systems like git.

```sh
# ~/.gitignore
.metals/
.bloop/
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
