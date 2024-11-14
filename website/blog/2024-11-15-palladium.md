---
author: Tomasz Godzik
title: Metals v1.4.0 - Palladium
authorURL: https://twitter.com/TomekGodzik
authorImageURL: https://github.com/tgodzik.png
---

We're happy to announce the release of Metals v1.4.1, which focuses mostly on
smaller fixes to the latest 1.4.0 release together with updating Bloop to the
latest version, which includes it's own improvements and fixes.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">37</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">36</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">4</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">7</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">0</td>
  </tr>
</tbody>
</table>

For full details:
[https://github.com/scalameta/metals/milestone/73?closed=1](https://github.com/scalameta/metals/milestone/73?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

## Bloop Update

Newest Bloop brings two main improvements for Metals users:

- Wart Remover should now work correctly, users will need to reimport the build.
- Best effort compilation issues should now be fixed and users are encouraged to
  tests it out by starting Metals with `-Dmetals.enable-best-effort=true` or by
  putting that property into `metals.serverProperties` in case of VS Code.

You can read about a specific changes in the
[release notes](https://github.com/scalacenter/bloop/releases/tag/v2.0.5)

## Miscellaneous

- bugfix: Fix extract function not showing up
  [tgodzik](https://github.com/tgodzik)
- bugfix: Properly escape jar: paths on Windows
  [tgodzik](https://github.com/tgodzik)
- bugfix: Run by default also synthetic mains such as scripts
  [tgodzik](https://github.com/tgodzik)
- bugfix: Correctly check if a given config exists in scalafix.conf
  [tgodzik](https://github.com/tgodzik)
- improvement: Always find all references including companion objects and
  classes. [tgodzik](https://github.com/tgodzik)
- bugfix: Fix pasting into multiline strings when indent would be wrong.
  [tgodzik](https://github.com/tgodzik)
- improvement: Don't automatically add release flag for versions from 17
  [tgodzik](https://github.com/tgodzik)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.4.0..v1.4.1
    16	Tomasz Godzik
    12	Scalameta Bot
     5	dependabot[bot]
     1	nguyenyou
```

## Merged PRs

## [v1.4.1](https://github.com/scalameta/metals/tree/v1.4.1) (2024-11-14)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.4.0...v1.4.1)

**Merged pull requests:**

- improvements: Explicitly use URI instead of string
  [\#6933](https://github.com/scalameta/metals/pull/6933)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Allow kebab case in inlay hints
  [\#6932](https://github.com/scalameta/metals/pull/6932)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix test for new project provider
  [\#6931](https://github.com/scalameta/metals/pull/6931)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt-buildinfo from 0.13.0 to 0.13.1
  [\#6927](https://github.com/scalameta/metals/pull/6927)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.16 to 2.1.17
  [\#6928](https://github.com/scalameta/metals/pull/6928)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix extract function not showing up
  [\#6920](https://github.com/scalameta/metals/pull/6920)
  ([tgodzik](https://github.com/tgodzik))
- chore: Only resolve jvm when running test
  [\#6919](https://github.com/scalameta/metals/pull/6919)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update bloop-rifle from 2.0.3 to 2.0.5
  [\#6916](https://github.com/scalameta/metals/pull/6916)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 10.20.1 to 10.21.0
  [\#6918](https://github.com/scalameta/metals/pull/6918)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.14 to 2.1.16
  [\#6917](https://github.com/scalameta/metals/pull/6917)
  ([scalameta-bot](https://github.com/scalameta-bot))
- docs: Upgrade Docusaurus to Version 3.6.0
  [\#6915](https://github.com/scalameta/metals/pull/6915)
  ([nguyenyou](https://github.com/nguyenyou))
- bugfix: Properly escape jar: paths
  [\#6913](https://github.com/scalameta/metals/pull/6913)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Also discover synthetic mains such as scripts
  [\#6910](https://github.com/scalameta/metals/pull/6910)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt-buildinfo from 0.12.0 to 0.13.0
  [\#6911](https://github.com/scalameta/metals/pull/6911)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt, scripted-plugin from 1.10.4 to 1.10.5
  [\#6912](https://github.com/scalameta/metals/pull/6912)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump react from 18.2.0 to 18.3.1 in /website
  [\#6905](https://github.com/scalameta/metals/pull/6905)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @swc/core from 1.7.14 to 1.7.42 in /website
  [\#6907](https://github.com/scalameta/metals/pull/6907)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps-dev): bump @types/node from 22.5.0 to 22.8.6 in /website
  [\#6906](https://github.com/scalameta/metals/pull/6906)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update sbt, scripted-plugin from 1.10.3 to 1.10.4
  [\#6903](https://github.com/scalameta/metals/pull/6903)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @easyops-cn/docusaurus-search-local from 0.44.5 to 0.45.0 in
  /website [\#6908](https://github.com/scalameta/metals/pull/6908)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- debug: Print only relevant information when rename isn't happening
  [\#6902](https://github.com/scalameta/metals/pull/6902)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Check all references at the same time
  [\#6899](https://github.com/scalameta/metals/pull/6899)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Correctly check if a given config exists in scalafix.conf
  [\#6901](https://github.com/scalameta/metals/pull/6901)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Always find all references
  [\#6898](https://github.com/scalameta/metals/pull/6898)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: When determining indent first check for pipe
  [\#6897](https://github.com/scalameta/metals/pull/6897)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Publish interfaces and mtagsShared for JDK 8
  [\#6895](https://github.com/scalameta/metals/pull/6895)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Don't add release flag for versions from 17
  [\#6894](https://github.com/scalameta/metals/pull/6894)
  ([tgodzik](https://github.com/tgodzik))
- refactor: Use codeAction interface for most code actions
  [\#6881](https://github.com/scalameta/metals/pull/6881)
  ([tgodzik](https://github.com/tgodzik))
- chore: Retry code actions for Scala CLi diags
  [\#6892](https://github.com/scalameta/metals/pull/6892)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.15.1 to 3.15.2
  [\#6891](https://github.com/scalameta/metals/pull/6891)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 10.20.0 to 10.20.1
  [\#6884](https://github.com/scalameta/metals/pull/6884)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.28.2 to 4.28.3
  [\#6883](https://github.com/scalameta/metals/pull/6883)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-ci-release from 1.8.0 to 1.9.0
  [\#6882](https://github.com/scalameta/metals/pull/6882)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Update Metals default versions
  [\#6880](https://github.com/scalameta/metals/pull/6880)
  ([tgodzik](https://github.com/tgodzik))
- docs: Add release notes for 1.4.0
  [\#6879](https://github.com/scalameta/metals/pull/6879)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): bump http-proxy-middleware from 2.0.6 to 2.0.7 in /website
  [\#6878](https://github.com/scalameta/metals/pull/6878)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
