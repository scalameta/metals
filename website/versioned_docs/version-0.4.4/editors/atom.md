---
id: version-0.4.4-atom
title: Atom
original_id: atom
---

Metals works with Atom thanks to the
[`ide-scala`](https://atom.io/packages/ide-scala) package.

![Atom demo](https://i.imgur.com/xPn2ATM.gif)


## Requirements

**Java 8**. Metals does not work with Java 11 yet so make sure the JAVA_HOME
environment variable points to Java 8.

**macOS, Linux or Windows**. Metals is developed on macOS and every PR is
tested on Ubuntu+Windows.

**Scala 2.12 and 2.11**. Metals works only with Scala versions 2.12.8, 2.12.7, 2.12.6, 2.12.5, 2.12.4, 2.11.12, 2.11.11, 2.11.10 and 2.11.9.
Note that 2.10.x and 2.13.0-M5 are not supported.

## Installing the package

Install the package by searching for "ide-scala" or run the following command.

```sh
apm install ide-scala
```

[![Install Metals package](https://img.shields.io/badge/metals-atom-brightgreen.png)](atom://settings-view/show-package?package=ide-scala)


## Importing a build

The first time you open Metals in a new workspace it prompts you to import the build.
Click "Import build" to start the installation step.

![Import build](https://i.imgur.com/WxfhMFz.png)

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


Update the server property `-Dmetals.sbt-script=/path/to/sbt` to use a custom
`sbt` script instead of the default Metals launcher if you need further
customizations like reading environment variables.


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

![Import sbt changes](https://i.imgur.com/xDb9oJU.png)


### Manually trigger build import

To manually trigger a build import, execute the "Import build" command through
the command palette (`Cmd + Shift + P`).

![Import build command](https://i.imgur.com/EGVO5Yb.png)

## Run doctor

Execute the "Run Doctor" through the command palette to troubleshoot potential
configuration problems in your workspace.

![Run doctor command](https://i.imgur.com/8ODqcUj.png)

## Using latest Metals SNAPSHOT

Update the "Metals version" setting to try out the latest pending Metals
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

