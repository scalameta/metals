* Choose the right version number:
  * `x.0.0` is reserved for incompatible changes and require a milestone cycle.
  * `x.y.0` is reserved for compatible changes.
  * `x.y.z` is reserved for bugfixes that don't change the public API,
* Tag the release:
  * The tag must be called `vx.y.z`, e.g. `v3.0.0`.
  * `git tag -a vx.y.z -m "vx.y.z"`
  * `git push upstream --tags`
  * Do not create a release on GitHub just yet. Creating a release on GitHub
    sends out a notification to repository watchers, and the release isn't ready
    for that yet.
* Wait for [the Travis CI job](https://travis-ci.org/scalameta/metals/branches)
  in Active Branches to build the binaries and stage them to Sonatype.
* While waiting for Travis, update the milestones:
  * https://github.com/scalameta/metals/milestones
  * Close the milestone or milestones corresponding to the release.
    For example, for 3.3.0, we closed both 3.2.1 and 3.3.0 (we never
    released 3.2.1, so all its tickets went straight to 3.3.0).
  * Create the milestone or milestones corresponding to future releases.
    For example, for 3.3.0, we created both 3.3.1 and 3.4.0.
* While waiting for Travis, draft the release notes:
  * Copy `website/blog/2018-12-06-iron.md` as a template
  * Open a PR to the repo
  * https://github.com/scalameta/metals/releases/new.
* Verify the Sonatype release:
  * Make sure that the release shows up at https://oss.sonatype.org/content/repositories/releases/org/scalameta/.
  * Run `./bin/test-release.sh $VERSION` to ensure that all artifacts have successfully been released.
* Upgrade downstream projects:
  * https://github.com/scalameta/metals-vscode: tag a new release and publish to Marketplace.
* Publish the release on GitHub:
  * https://github.com/scalameta/metals/releases
  * Copy-paste the release from the website blog
  * In the dropdown, pick the recently pushed tag.
  * In the release title, say `Metals vX.Y.Z`.
  * Once the VS Code extension has been updated on the Marketplace,
    click "Publish release".
* Announce on Gitter: https://gitter.im/scalameta/metals
  * Tag everybody with `@/all`
