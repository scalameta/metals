---
id: releasing
title: Making a release
---

## Metals

### Tag the release

- Choose the right version number:

  - `x.0.0` should not be used unless Metals is totally reworked.
  - `x.y.0` is reserved for changes that require adjustments in the plugins for
    different editors.
  - `x.y.z` is reserved in remaining cases, where the changes do not require any
    adjustments in the plugins.

  For most releases bumping `z` is enough especially that Metals not being used
  as a library anywhere and do not have a public API.

  - The tag must be called `vx.y.z`, e.g. `v3.0.0`.
  - `git tag -a vx.y.z -m "vx.y.z"`

  You will need the tag to fill in some information in the release notes. It can
  always be deleted and tagged again if you want to include more commits.
  `git tag -d vx.y.z`

  Please wait with pushing the tag until the release notes are accepted.

### Draft the release notes

First of all, if you are bumping the minor part of the version choose a new
metal or an alloy as a new for the release! Use that in the release notes and in
the release title on GitHub. Otherwise, use the metal from the previous release.

You might use the `./bin/merged_prs.sc` script to generate a draft of release
notes with merged PRs list between two last release tags. It can be run using
scala-cli:

```
cs install scala-cli
scala-cli ./bin/merged_prs.scala -- <tag1> <tag2> "<github_api_token>"
```

Make sure that required tags, the previous one and the new one, are available.
You might need to do `git fetch <main_fork> --tags` to fetch the older tag. You
need to create the new one.

It will need a [basic github API token](https://github.com/settings/tokens)
(don't need any additional scopes) to run, which may be specified via the last
argument.

The script will generate a new markdown file in `website/blog` filled with a
basic release template.

You can fill in the number of closed issues from the last milestone, though you
will need to make sure everything is included there. In most cases you can just
add all the closed issues since the last milestone.

Please also fill in any missing details like the author or author image.

To write the actual release notes you can look through the list of closed PR,
put any smaller changes that you think are worth mentioning to the users in the
`Miscellaneous` section and any large ones as their own section with more
explanation and examples.

If you want to add images to the release notes, you can add them to the separate
folder in the `gh-pages-images` repository and reference them in the release
notes using the
`![image-name](https://github.com/scalameta/gh-pages-images/blob/master/metals/<release-name>/image-name.gif?raw=true)`
format.

### Update Metals version

- `build.sbt` - update `localSnapshotVersion` and `mimaPreviousArtifacts`
- `.github/ISSUE_TEMPLATE/bug_report.yml` - update `Version of Metals`
- `./bin/test-release.sh` - remove any unsupported Scala versions and add newly
  supported ones. This will be needed later to test the new release.

### Open a PR with release notes

Open the PR to the repo and wait until they are approved to merge them. This
might take some time if the release is large enough.

### Start the release process:

- `git push <main_fork> <tag_name>` will trigger release workflow on the main
  fork of the metals repository. This for example can be
  `git push git@github.com:scalameta/metals.git v1.0.0` or if you have the
  remote set up:

```bash
> git remote -v
primary	git@github.com:scalameta/metals.git (fetch)
primary	git@github.com:scalameta/metals.git (push)
> git push primary v1.0.0
```

- Do not create a release on GitHub just yet. Creating a release on GitHub sends
  out a notification to repository watchers, and the release isn't ready for
  that yet.

- Wait for
  [the Github Actions job](https://github.com/scalameta/metals/actions?query=workflow%3ARelease)
  to build the binaries and stage them to Sonatype.

- While waiting for Github Actions, update the milestones:

  - https://github.com/scalameta/metals/milestones
  - Close the milestone or milestones corresponding to the release. For example,
    for a v3.3.0 release, we close both 3.3.0 and 3.2.1 (because we never
    released 3.2.1, so all its tickets went straight to 3.3.0).
  - Create the milestone or milestones corresponding to future releases. For
    example, for a v3.3.0 release, we create both v3.3.1 and v3.4.0.

### Before release announcement

- Verify the Sonatype release:

  - Make sure that the release shows up at
    https://oss.sonatype.org/content/repositories/releases/org/scalameta/.
  - Run `./bin/test-release.sh $VERSION` to ensure that all artifacts have
    successfully been released. It's important to ensure that this script passes
    before announcing the release since it takes a while for all published
    artifacts to sync with Maven Central. You might need to update the script if
    the list of supported versions changed in the meantime.
  - To check that the release to Sonatype succeed even if the artifacts are not
    yet available on Maven Central run:
    `./bin/test-release.sh $VERSION -r sonatype:public`

- Double check if the release starts up and some basic features work.
- Merge the release notes PR
- Wait until it's available on https://scalameta.org/metals/blog/
- Upgrade downstream projects:

  - https://github.com/scalameta/metals-vscode:
    - generate metals website with `sbt docs/run`
    - open `website/target/docs/editors/vscode.md` and copy everything from
      "Installation" over to the scalameta/metals-vscode README
      - remove "Using latest SNAPSHOT" section, this table is only up-to-date on
        the website
    - check or update `enum` values of `fallbackScalaVersion` property in
      `package.json`. They should be the same as `V.supportedScalaVersions` in
      `build.sbt`
    - update `metals.serverVersion` in `package.json` and if needed also
      `metals.bloopVersion` if it was set previously to the version within
      metals.
    - open a PR, feel free to merge after CI is green
    - open the last generated release draft, tag with a new version and publish
      the release. The new version should always be the next minor since patches
      are used for prerelease versions, so if the last full release was `1.24.0`
      then the next should be `1.25.0`. This will start github actions job and
      publish the extension to both the Visual Studio Code Marketplace and
      openvsx.

### Official release

- Publish the release on GitHub:

  - https://github.com/scalameta/metals/releases
  - Copy-paste the release from the website blog
  - In the dropdown, pick the recently pushed tag.
  - In the release title, say `Metals vX.Y.Z`.
  - Once the VS Code extension has been updated on the Marketplace, click
    "Publish release".

- Announce the new release with the link to the release notes:
  - on [Discord](https://discord.com/invite/RFpSVth)

### Post release

See if any docs need to be updated due to the changes in the last PR. This could
potentially be done also before the release, but might be easier as a follow up
afterwards without the release pressure.

## Sanity check

- [ ] draft release notes and create with PR with them
- [ ] bump Metals version
- [ ] push a tag to the repository
- [ ] merge PR with release notes
- [ ] check if artifacts are published to the sonatype
- [ ] update downstream projects like metals-vscode
- [ ] do release on GitHub
- [ ] announce it

## Add new Scala version support to the existing release

- If it's a Scala2 you need to release semanticdb plugin for it first.

  - Find out which scalameta version the existing release uses
  - In scalameta project:
    - checkout on the tag for this version
    - apply required changes for supporting new Scala2 compiler
    - run manually the
      [release workflow](https://github.com/scalameta/scalameta/actions/workflows/release-custom.yml)

- Release mtags artifact. To do this you also need to run manually the
  [release workflow](https://github.com/scalameta/scalameta/actions/workflows/release-custom.yml).
  You should first check if any manual changes are needed for that version. If
  they are, you need to apply them on a separate branch and then run the
  workflow from that branch.
