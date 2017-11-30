> ⚠️ This project is very alpha stage. Expect bugs and surprising behavior. Ticket reports and patches are welcome!

# Installation
The following guide provides instructions on how to try this on a real project, with the purpose
of collecting valuable feedback from real-world scenarios.

## Step 1 - sbt plugin
The server needs to access some metadata about the build configuration. This data are produced by
an sbt plugin. This plugin is currently not published, so you will need to copy paste it on your machine.
You have two options: enable the plugin globally (so that it will be available in all projects) or
locally to a single project.

Here's the source of the plugin: https://github.com/scalameta/language-server/blob/master/project/ScalametaLanguageServerPlugin.scala

### Globally
Copy the source to either (depending on your sbt version):
- `~/.sbt/0.13/plugins/project/ScalametaLanguageServerPlugin.scala` (sbt 0.13)
- `~/.sbt/1.0/plugins/project/ScalametaLanguageServerPlugin.scala` (sbt 1.0)

### Locally
Copy the source to `/path/to/yourproject/project/ScalametaLanguageServerPlugin.scala`

## Step 2 - build the VSCode extension
The VSCode extension is not yet published on the Marketplace, so you'll need to build it locally.
For this step, `node` and `npm` are required.

- `cd vscode-extension`
- `npm install`
- `npm run build`
- `code --install-extension vscode-scalameta-0.0.1.vsix`

## Step 3 - publish the server locally
From the repo root run `sbt publishLocal`

# Usage
In your project of choice, open `sbt` in your project and run `*:scalametaEnableCompletions`.
As mentioned above, this will produce the necessary metadata for the server.

> **NOTE**: you will need to repeat this step every time you add a new source file to the project

Open your project in VSCode (`code .` from your terminal) and open a Scala file; the server will now start.

Please note that it may take a few seconds for the server to start and there's currently no explicit
indication that the server has started (other than features starting to work).
To monitor the server activity, we suggest to watch the log file in your project's target directory,
for instance: `tail -f target/metaserver.log`.

Finally, since most features currently rely on a successful compilation step, make sure you incrementally
compile your project by running `~compile` in `sbt`.
