---
id: releasing
title: Making a release
---

## Metals

### Tag the release

- Choose the right version number:

  - `x.0.0` is reserved for incompatible changes and require a milestone cycle.
  - `x.y.0` is reserved for compatible changes.
  - `x.y.z` is reserved for bugfixes that don't change the public API,

  For most releases bumping `z` is enough especially that Metals not being used
  as a library anywhere and do not have a public API.

  - The tag must be called `vx.y.z`, e.g. `v3.0.0`.
  - `git tag -a vx.y.z -m "vx.y.z"`

  You will need the tag to fill in some information in the release notes.
  It can always be deleted and tagged again if you want to include more commits.
  `git tag -d vx.y.z`

  Please wait with pushing the tag until the release notes are accepted.

### Draft the release notes

You might use the `./bin/merged_prs.sc` script to generate merged PRs list
between two last release tags. It can be run using scala-cli:

```
cs install scala-cli 
scala-cli ./bin/merged_prs.sc -- <tag1> <tag2> "<github_api_token>"
```

It will need a [basic github API token](https://github.com/settings/tokens) (don't need any additional scopes) to run, which may be specified via
environment variable `GITHUB_TOKEN` or via the last argument.

The script will generate a new markdown file in `website/blog` filled with a
basic release template.

You can fill in the number of closed issues from the last milestone, though
you will need to make sure everything is included there.

### Update Metals version

- `build.sbt` - update `localSnapshotVersion`
- `.github/ISSUE_TEMPLATE/bug_report.yml` - update `Version of Metals`
- `./bin/test-release.sh` - remove any unsupported Scala versions and
  add newly supported ones. This will be needed later to test the new release.
- `.github/workflows/mtags-auto-release.yml` - update `metals_version` and `metals_ref`

### Open a PR with release notes

Open the PR to the repo https://github.com/scalameta/metals/releases/new.

### Start the release process:

- `git push upstream --tags` will trigger release workflow
- Do not create a release on GitHub just yet. Creating a release on GitHub
  sends out a notification to repository watchers, and the release isn't ready
  for that yet.

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

- Make sure all docs are up to date.

- Upgrade downstream projects:

  - https://github.com/scalameta/metals-vscode:
    - generate metals website with `sbt docs/run`
    - open `website/target/docs/editors/vscode.md` and copy everything from "Requirements" over to the scalameta/metals-vscode README
      - remove "Using latest SNAPSHOT" section, this table is only up-to-date on the website
    - check or update `enum` values of `fallbackScalaVersion` property in `package.json`.
      They should be the same as `V.supportedScalaVersions` in `build.sbt`
    - open a PR, feel free to merge after CI is green
    - open the last generated release draft, tag with a new version and publish
      the release. This will start github actions job and publish the extension
      to both the Visual Studio Code Code Marketplace and openvsx.

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
    - create and push tag with the following name: `semanticdb_v${existing-scalameta-version}_${scala-version}`
      Notice this tag should include [these changes in `release.yml`](https://github.com/scalameta/scalameta/pull/2562/commits/1dfc99677659f5a9919c0dc9166547a0b332d35c)

- Release mtags artifact.
  Open [`Mtags auto release` action page](https://github.com/scalameta/metals/actions/workflows/mtags-auto-release.yml),
  click `Run Workflow`, specify Scala version and confirm.
