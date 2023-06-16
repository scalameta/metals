---
author: Vadim Chelyshov
title: Towards better releases
authorURL: https://twitter.com/_dos65
authorImageURL: https://github.com/dos65.png
---

As many of you might have noticed, the previous `0.11.0` release didn't go smoothly due to a number of issues that came to light only after the release was published.
Some of them were quite critical and the only option to continue to work was to downgrade Metals until `0.11.1` arrived.

We apologize for that!

However, this post is not only about saying sorry.
In order to avoid such situations in the future we have taken it upon ourselves to take steps that will allow us to detect issues earlier.

There were two main improvements that were implemented since the last release:
- Decouple Metals releases from Scala releases
- Pre-release version for VSCode extension

## Decouple Metals releases from Scala releases

We haven't mentioned this in the release notes for `0.11.0`, but since that version we have changed the way Metals detects whether a specific version is supported and thanks to that we are now able to backpublish new Scala versions support.
The support of Scala `3.1.1` Scala was added to Metals `0.11.1` using this new mechanism.
It allows us to provide new Scala version support much faster than before and also support Scala3-NIGTHTLY versions.

The additional benefit from this approach is that it eliminates time pressure for future Metals releases.
If you look at the release notes for previous versions, almost every one brings at least one new compiler version support.
Having this limitation and the need to provide the new release as soon as possible always increases the chances of something affecting the release. So, now with this new feature we are able to take our time for final fixes and do releases with more confidence.

The current state for Scala versions support is:
- Every Metals release comes with support to all known supported Scala versions + last 5 latest Scala3-NIGHTLY versions.
  This is applied to SNAPSHOT releases too.
- In case a new Scala version appear, the latest Metals release will receive its support automatically.
  For example, the next Scala `3.1.2`  will be supported only by the latest Metals `0.11.1` but not by `0.11.0`.
  That works for Scala3-NIGHTLY versions too. Metals has a scheduled daily job that publishes artifacts for newly discovered NIGHTLY versions.


## Pre-release version for VSCode extension

Another great feature that was added was the possibility to use the `pre-release` versions of the Metals extension.
If you open the Metals extension page, you will find a `Switch to Pre-release version` button.
This version is like a snapshot, it's published for every change in the [scalameta/metals-vscode](https://github.com/scalameta/metals-vscode) repository.

It allows to test not yet released features, as well as to check if everything works fine for your workspace using the latest main branch. 
Some issues might be observed only under the particular version of the client as was the case with the ill fated 0.11.0 release.

Also, there is a new setting `Metals: Suggest Latest Upgrade` that is enabled by default for `pre-release`.
If you have it enabled, you will receive notifications with an option to upgrade Metals server version to the latest snapshot once a day.

We hope that some brave users will start using this `pre-release` version and report issues if you encounter any.
This will help us spot problems earlier.

_Notice_:
Using pre-release versions may result in a less stable experience.
In case of issues, if you are switching back from `pre-release` to `release` you also need to downgrade `Metals: Server Version` manually. The actual version might be found at [docs page](https://scalameta.org/metals/docs/#latest-metals-server-versions) or [latest.json](https://scalameta.org/metals/latests.json)
