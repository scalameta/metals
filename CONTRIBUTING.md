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

### Using Bleeding Edge Builds

> NOTE: these are temporary instructions until we figure out a release process
> that is more standard/conventional at Databricks, ideally via our own s3
> buckets that can be accessed from Arca without custom credentials.

We publish a Metals release for every merged commit. First, get the latest
version number of the
[metals_2.13](https://github.com/REDACTED_ORG/metals/packages/2620091)
package. Optionally, use the `gh` CLI tool to fetch this version number
automatically.

```
$ gh auth login --scopes "read:packages"
$ gh api orgs/REDACTED_ORG/packages/maven/org.scalameta.metals_2.13/versions | jq -r '.[0].name'
1.5.1-DATABRICKS-8-1-79000f48 ```

Next, create a "Classic" personal access token
(https://github.com/settings/tokens/new) with the `read:packages` scope.
Importantly, you **must** configure SSO for the REDACTED organization
after creating the token. Note that you can't use `gh auth token` because it's
not a "classic" token.

Next, add the GitHub personal access token to your Coursier credentials
configuration (https://get-coursier.io/docs/other-credentials).

```sh
$ mkdir -p ~/.config/coursier
$ cat > ~/.config/coursier/credentials.properties <<EOF
gh.username=YOUR_GITHUB_USERNAME # e.g. olaf-geirsson_data
gh.password=ghp_YOUR_TOKEN # must have a "ghp_" prefix
gh.host=maven.pkg.github.com
EOF
```

Next, update the Cursor setting "Metals: Server Version" and "Custom
Repositories" settings to use latest Metals version from our custom GH Packages
repository. It's simplest to edit `.vscode/settings.json` directly.

```jsonc
$ cat .vscode/settings.json
  // ...
  "metals.serverVersion": "1.5.1-DATABRICKS-8-1-79000f48",
  // NOTE: Custom repositories will be unnecessary after the PR
  // https://REDACTED_GITHUB_URL/universe/pull/1302962 is merged and live
  // on Arca
  "metals.customRepositories": [
    "ivy:file:///opt/databricks/ivy2/[defaultPattern]",
    "ivy2local",
    "central",
    "https://maven.pkg.github.com/REDACTED_ORG/metals"
  ]
  // ...
```

Finally, run the command "Developer: Restart Extension Host" (or "Reload
Window") to restart the Metals extension with the new version.

Optionally, confirm that you are running the correct version of Metals.

```sh
$ head -n 1 .metals/metals.log
2025.09.14 11:55:09 INFO  Started: Metals version 1.5.1-DATABRICKS-8-1-79000f48 in folders '/home/REDACTED_USER/metals' for client Cursor 1.99.3.
```

### Publishing a Release

First, add a new `git release` alias to your `~/.gitconfig` file.

```
❯ cat ~/.gitconfig
[user]
	name = Olafur Geirsson
	email = olafurpg@gmail.com
...
[alias]
  release = !git tag -fa "v$1" -m "v$1" && git push -f origin "v$1"
```

Next, make sure you are running on the latest `databricks` branch.

```sh
git checkout databricks
git pull origin databricks
```

Finally, run `git release VERSION` to publish a release:

```
❯ git release 1.5.1-DATABRICKS-9
```

This will create a new tag and (force) push it to the remote repository, which
triggers a new CI job to publish the artifact to the GitHub Packages registry.
