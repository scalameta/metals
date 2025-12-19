# Contributing

Please refer to the documentation for contributors
[on our website](https://scalameta.org/metals/docs/contributors/getting-started.html).

## Metals at Databricks

This section contains Databricks-specific instructions for contributing to
Metals.

- To work in arca, clone the Metals repo to `$HOME/metals` and run
  `arca ide cursor metals` to open the repo in Cursor via SSH Remote.
- You need Java 17 installed on your machine. The simplest way to do this is to
  install [`mise`](https://mise.jdx.dev/getting-started.html)
  (`curl https://mise.run | sh`), cd into the Metals directory and run
  `mise install`. You can also use SDKMAN or Coursier instead of Mise.
- Maybe not Databricks-specific, but run the command "Switch Build Server" and
  pick "bloop". Run `sbt bloopInstall` manually when you tweak the build and
  trigger "Connect to Build Server".
- Run `git config core.hookspath .git/hooks` to override the global pre-push
  formatting hook on Arca. Without this setting, your PRs will fail on
  formatting issues.

