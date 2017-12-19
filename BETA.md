> ⚠️ This project is very alpha stage. Expect bugs and surprising behavior. Ticket reports and patches are welcome!

The following guide provides instructions on how to try this on a real project, with the purpose
of collecting valuable feedback from real-world scenarios.

# Global setup
These steps are required once per machine.

## Step 1 - sbt plugin
The server needs to access some metadata about the build configuration. This data are produced by
an sbt plugin. This plugin is currently not published, so you will need to copy paste it on your machine.

Here's the source of the plugin: https://github.com/scalameta/language-server/blob/master/project/ScalametaLanguageServerPlugin.scala

Copy the source to either (depending on your sbt version):
- (sbt 0.13) `~/.sbt/0.13/plugins/project/ScalametaLanguageServerPlugin.scala`
- (sbt 1.0) `~/.sbt/1.0/plugins/ScalametaLanguageServerPlugin.scala`

## Step 2 - build the VSCode extension
The VSCode extension is not yet published on the Marketplace, so you'll need to build it locally.
For this step, `node` and `npm` are required.

- `cd vscode-extension`
- `npm install`
- `npm run build`
- `code --install-extension vscode-scalameta-0.0.1.vsix`

## Step 3 - publish the server locally
From the repo root run `sbt publishLocal`

# Per-project setup
These steps are required on each project.

## Step 1 - add semanticdb-scalac compiler plugin to your project
Some features of the server rely on the artifacts produced by the [`semanticdb-scalac`](http://scalameta.org/tutorial/#semanticdb-scalac) compiler plugin.
There are two alternative ways to install `semanticdb-scalac`.

The first option is to enable `semanticdb-scalac` permanently for your project in `build.sbt` with:

```scala
libraryDependencies += compilerPlugin("org.scalameta" % "semanticdb-scalac" % "2.1.2" cross CrossVersion.full)
scalacOptions += "-Yrangepos"
```

The second option is to enable `semanticdb-scalac` only for an active sbt session by running `semanticdbEnable` from the sbt shell.
```scala
$ sbt
> semanticdbEnable // automatically runs libraryDependencies += compilerPlugin(...)
> compile // re-compile project with semanticdb-scalac compiler plugin
> ...
```
As soon as you exit the sbt shell you need to re-run `semanticdbEnable` next time you open sbt.

## Step 2 - produce the build metadata
In your project of choice, open `sbt` and run `*:scalametaEnableCompletions`.
As mentioned above, this will produce the necessary metadata for the server.

> **NOTE**: you will need to repeat this step every time you add a new source file to the project or if you run `sbt clean`

## Step 3 - start editing
Open your project in VSCode (`code .` from your terminal) and open a Scala file; the server will now start.

Please note that it may take a few seconds for the server to start and there's currently no explicit
indication that the server has started (other than features starting to work).
To monitor the server activity, we suggest to watch the log file in your project's target directory,
for instance: `tail -f target/metaserver.log`.

Finally, since most features currently rely on a successful compilation step, make sure you incrementally
compile your project by running `~compile` in `sbt`.
