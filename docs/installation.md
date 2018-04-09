> ⚠️ This project is very alpha stage. Expect bugs and incomplete documentation.

The following instructions are intended for contributors who want to try Metals
and provide feedback. We do not provide support for day-to-day usage of Metals.

# Global setup

These steps are required once per machine.

## sbt plugin

The server needs to access some metadata about the build configuration. This
data are produced by an sbt plugin.

You can install the plugin with (replace `<version>` with the latest available version)

[![Download](https://api.bintray.com/packages/scalameta/sbt-plugins/sbt-metals/images/download.svg)](https://bintray.com/scalameta/sbt-plugins/sbt-metals/_latestVersion)

```scala
addSbtPlugin("org.scalameta" % "sbt-metals" % "<version>")
```

You can add the plugin to a specific project (adding it to `project/plugins.sbt`) or globally adding it to:

- (sbt 1) `~/.sbt/1.0/plugins/plugins.sbt`
- (sbt 0.13) `~/.sbt/0.13/plugins/plugins.sbt`

## VSCode extension
The VSCode extension is [published on the Marketplace](https://marketplace.visualstudio.com/items?itemName=scalameta.metals).
You can open VSCode and search for it or click [here](vscode:extension/scalameta.metals) to install
it directly.

# Per-project setup

These steps are required on each project.

## Quick-start
The quickest way to get started with Metals is to use the `metalsSetup` command in sbt.

```
sbt
> metalsSetup
```

The command will create the necessary metadata in the `.metals` directory
(which you should not checkout into version control) and setup the `semanticdb-scalac` compiler
plugin for the current sbt ession.

You should not checkout the `.metals` directory into version control. We recommend to add it to your
project's `.gitignore` or/and to your global `.gitignore`:

```
echo ".metals/" >> .gitignore
```

Note that you will need to invoke `metalsSetup` (or `semanticdbEnable`) whenever you close and
re-open sbt. For a more persistent setup, keep reading.

## Persisting the semanticdb-scalac compiler plugin
Some features like definition/references/hover rely on artifacts produced by a compiler plugin
called `semanticdb-scalac`.

`metalsSetup` enables the plugin on the current session (by invoking `semanticdbEnable`), but you
can choose to enable it permanently on your project by adding these two settings in your sbt build
definition:

```scala
addCompilerPlugin(Metals.semanticdbScalac)
scalacOptions += "-Yrangepos"
```

## Start editing
Open your project in VSCode (`code .` from your terminal) and open a Scala file;
the server will now start.

Please note that it may take a few seconds for the server to start and there's
currently no explicit indication that the server has started (other than
features starting to work). To monitor the server activity, we suggest to watch
the log file in your project's target directory, for instance:
`tail -f .metals/metals.log`. Alternatively, you can watch this log in the
VSCode output panel (selecting Metals on the right).

Finally, since most features currently rely on a successful compilation step,
make sure you incrementally compile your project by running `~compile` in `sbt`.
