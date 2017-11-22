# Contributing

:warning: This project is very alpha, expect bugs!
These instructions are intended for contributors to get a productive workflow when developing the plugin.

This project follows [scalameta's contribution guidelines].
Please read them for information about how to create good bug reports and submit pull requests.


## Project structure
- `languageserver` contains a Scala implementation of the [Language Server Protocol specification](https://github.com/Microsoft/language-server-protocol/blob/master/protocol.md) (this is a fork of https://github.com/dragos/dragos-vscode-scala)
- `metaserver` contains a Scala implementation of a language server based on Scalameta, which uses `languageserver`
- `vscode-extension` contains a Visual Studio Code extension, implementing a client for the language server
- `test-workspace` directory for manually testing the plugin locally


## Running locally

First you need to have the following applications installed

- `git`
- `sbt`
- Visual Studio Code (`code` from the console)
- `npm`

To try out the language-server locally, it's best to keep two open terminal
windows.

```
git clone https://github.com/scalameta/language-server.git
cd language-server

########
# Step 1
########
sbt
> metaserver/publishLocal # publish your latest changes locally
                          # keep this sbt shell session open, and
                          # re-run publishLocal every time you
                          # edit *.scala sources.
> ~testWorkspace/test:compile # compile the sources in test-workspace

########
# Step 2
########
# Inside a new terminal window
cd vscode-extension
npm install
code vscode-extension
> Debug > "Start debugging" (shortcut: F5)
          # Inside vscode, F5 will open a new window with the latest
          # metaserver/publishLocal of the plugin installed.
          # Close the window and run F5 again after every
          # metaserver/publishLocal
> File > Open > language-server/test-workspace (shortcut Cmd+O on MacOS)
          # Open the test-workspace folder in the debugging window
          # of vscode. Open a file in the project.

(optional) to install the plugin for your default vscode
npm run build # builds a .vsix extension file
code --install-extension vscode-scalameta-0.0.1.vsix
```

To test the plugin on another project than `test-workspace`, you must
have the Scalameta `semanticdb-scalac` compiler plugin enabled.
You have two alternatives:

1. [sbt-scalafix](https://scalacenter.github.io/scalafix/docs/users/installation#sbt-scalafix),
   mostly automatic with `addSbtPlugin`.
2. [semanticdb-scalac](http://scalameta.org/tutorial/#sbt), manually
   enable the compiler plugin in your project.
   This step should work similarly for other build tools than sbt.

See an example manual installation in [test-workspace/build.sbt](test-workspace/build.sbt).

## Unit tests

So far, we manually test the integration with vscode/LSP.
However, we have a few unit tests for the parts unrelated to LSP or vscode.
To run these tests,
```
sbt
> metaserver/test                     # Run all unit tests
> metaserver/testOnly -- tests.ctags  # Only test the ctags tests
> metaserver/testOnly -- tests.search # Only test the symbol indexer tests
```

## Clearing index cache

This project caches globally in the computed symbol indices from your
source classpath.
This is done to prevent reindexing large dependencies like the JDK
(which has ~2.5 million lines of code) on every editor startup.
This directory where this cache is stored depends on your OS and is
computed using [soc/directories](https://github.com/soc/directories)
using the project name "metaserver".
For example, on a Mac this directory is ~/Library/Caches/metaserver/.
While developing this project, you may encounter the need to need
`rm -rf` this cache directory to re-trigger indexing for some reason.


## Troubleshooting

- If SymbolIndexerTest.classpath tests fail with missing definitions for
  `List` or `CharRef`, try to run `*:scalametaEnableCompletions` from the
  sbt shell and then re-run. This command must be re-run after every
  `clean`.
- If you get the following error

      org.fusesource.leveldbjni.internal.NativeDB$DBException: IO error: lock /path/to/Library/Cache/metaserver

  that means you are running two scalameta/language-server instances
  that are competing for the same lock on the global cache.
  Try to turn off your editor (vscode/atom) while running the test suite
  locally.
  We hope to address this in the future by for example moving the cache to
  each workspace directory or use an alternative storing mechanism.
