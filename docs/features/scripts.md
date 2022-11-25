---
id: scripts
title: Scripts support
---

### Working with a script

Whenever, Metals opens a script file with `*.sc` extension, but only when it's
not `*.worksheet.sc` or a build server has not already started for it, users will
be prompted to choose whether they want to use [Ammonite](http://ammonite.io/)
or [Scala CLI](https://scala-cli.virtuslab.org/) to power the script.

![scala-cli](https://i.imgur.com/ghR1Src.gif)

Scripts are usually used for smaller programs and the main difference is that
statements can be put on top level without a need for additional objects or
methods.

Once a user has chosen a specific scripting tool, Metals will also suggest to
import all newly opened scripts automatically or to always to do that manually.

Both tools will start a build server in the background that will be able to
compile your code and provide Metals with all the necessary information.

Scala CLI scripts can also be used standalone when Scala CLI generated `.bsp`
directory after running `scala-cli compile` or `scala-cli setup-ide` on that
script.

### Advanced information

Additionally, advanced users can start the underlying build server manually with
`Metals: Start Scala CLI BSP server` (`scala-cli-start`) or
`Metals: Start Ammonite BSP server` (`ammonite-start`). They will also be able
to stop it with `Metals: Stop Scala CLI BSP server`(`scala-cli-stop`) or
`Metals: Stop Ammonite BSP server`(ammonite-stop). These commands can be used
for more fine grained control when to turn on or of scripting support. This is
especially useful since the additional build server running underneath can take
up some additional resources.

If the script is in a dedicated folder, by default we will treat all the scripts
and scala files in that directory as ones that can be used together. So you
would be able to import method and classes from those files. However, if the
script is contained within a directory that also contains other sources, that
script will be treated as a standalone one in order to avoid flaky compilation
errors coming from normal files in the workspace.

Current limitations can be found:

- [here for Ammonite](https://github.com/scalameta/metals/issues?q=is%3Aopen+is%3Aissue+label%3A%22ammonite+support%22)
- [here for Scala CLI](https://github.com/scalameta/metals/issues?q=is%3Aopen+is%3Aissue+label%3Ascala-cli)

For troubleshooting that a look at the [FAQ](/docs/troubleshooting/faq#ammonite-scripts)