# Contributing

:warning: This project is very alpha, expect bugs!
These instructions are intended for contributors to get a productive workflow when developing the plugin.

This project follows [scalameta's contribution guidelines].
Please read them for information about how to create good bug reports and submit pull requests.

## Features

- [x] Lint with Scalafix on compile
- [x] Formatting with Scalafmt
- [x] Go to definition in project sources using Semanticdb
- [ ] Go to definition in dependencies
- [ ] Red squigglies as you type
- [ ] Auto completions
- [ ] Signature helper (show parameter list)
- [ ] Show type at position
- [ ] Find references
- [ ] Show docstring


## Project structure
- `language-server` contains a Scala implementation of a language server
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
> language-server/publishLocal # publish your latest changes locally
                               # keep this sbt shell session open, and
                               # re-run publishLocal every time you
                               # edit *.scala sources.

########
# Step 2
########
# Inside a new terminal window
cd vscode-extension
npm install
code vscode-extension
> Debug > "Start debugging" (shortcut: F5)
          # Inside vscode, F5 will open a new window with the latest
          # language-server/publishLocal of the plugin installed.
          # Close the window and run F5 again after every
          # language-server/publishLocal
> File > Open > language-server/test-workspace (shortcut Cmd+O on MacOS)
          # Open the test-workspace folder in the debugging window
          # of vscode. Open a file in the project.

########
# Step 3
########
# Inside the same terminal window as step 2
cd ../test-workspace
sbt        # Open up long running sbt shell
> ~compile # Compile sources on file edit
```

To test the plugin on another project than `test-workspace`, you must
have the Scalameta `semanticdb-scalac` compiler plugin enabled.
See example installation in `test-workspace/build.sbt` or
http://scalameta.org/tutorial/#sbt
The plugin should be able to work with any build tool, as long as
you have the `semanticdb-scalac` compiler plugin enabled.

