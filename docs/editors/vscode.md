---
id: vscode
title: Visual Studio Code
---

The Metals VS Code extension is developed in the main Metals repo and is the
best tested editor integration. The VS Code extension supports all available
functionality of the Metals server.

![Goto Definition](https://user-images.githubusercontent.com/1408093/48776422-1f764f00-ecd0-11e8-96d1-170f2354d50e.gif)

```scala mdoc:requirements

```

## Installing the extension

The Metals extension not yet published to the Marketplace. To install the
extension, you will need to build it from source using the
[getting started instructions](../contributors/getting-started.html) for
contributors.

## Importing a build

Metals can automatically import sbt builds for v0.13.16+ and v1.0.0+. Importing
an sbt build involves generating [Bloop](https://scalacenter.github.io/bloop/)
JSON files describing project library dependencies and Scala compiler options.

First, open VS Code in a directory containing an sbt build. When Metals
encounters a new sbt build it will prompt you to "Import build via Bloop".

![Import build via Bloop](assets/import-via-bloop.png)

Click "Import build via Bloop" to start the `sbt bloopInstall` import step.

![sbt bloopInstall](assets/sbt-bloopinstall.png)

While the `sbt bloopInstall` step is running, no Metals functionality will work.

This step can take a long time, especially the first time you run it in a new
workspace. The exact time depends on the complexity of the build and if library
dependencies are cached or need to be downloaded. For example, this step can
take everything from 10 seconds in small cached builds up to 10-15 minutes in
large uncached builds.

Once the import step completes, compilation starts for your open `*.scala`
files. Once the sources have compiled successfully, you can navigate the
codebase with "goto definition" by `Cmd+click` or `F12`.

When you change `build.sbt` or sources under `project/`, you will be prompted to
re-import the build.

![Import sbt changes](assets/sbt-import-changes.png)

Click "Import changes" and that will restart the `sbt bloopInstall` step. If you
dismiss this notification by pressing `x`, you will not be prompted again for a
short period of time.

To manually trigger a build import, execute the "Import build" command by
opening the "Command palette" (`Cmd + Shift + P`) and search for "import build".

![Import build command](assets/vscode-import-build.png)
