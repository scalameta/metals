---
authors: kmarek
title: Metals v1.5.0 - Strontium
---

We're happy to announce the release of Metals v1.5.0, which brings an array of bugfixes and improvements, as we keep working on Metals stability.
<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">64</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">45</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">11</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center"></td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">0</td>
  </tr>
</tbody>
</table>

For full details: [https://github.com/scalameta/metals/milestone/75?closed=1](https://github.com/scalameta/metals/milestone/75?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!


- [Support for 2.13.16](#support-for-2.13.16)
- [Fix for Metals stopped compiling bug](#fix-for-Metals-stopped-compiling-bug)
- [Error reports improvements](error-reports-improvements)
- [Inlay hints for worksheets](inlay-hints-for-worksheets)

## Support for 2.13.16
With the new release comes support for Scala 2.13.16. You can read release highlighs in [the Scala 2.13.16 release notes](https://github.com/scala/scala/releases/tag/v2.13.16).

## Fix for Metals stopped compiling bug
A bug where Metals would stop compiling the code and failed to report any errors. This bug was related to a feature, where Metals would pause compilation in certain scenarions like focusing out of editor Window. The feature was removed completely, since there was little benefit from it and it was tricky to make it work. If you expirenced this bug or a similar one, let us know if this release fixes in for you. Thanks go to [tgodzik](https://github.com/tgodzik) for debugging this issue.

## Error reports improvements
We made small improvements to our error reporting. The reports will be now better deduplicated, also the information about created reports will be now logged to improve their visibility. As previously, full list of generated reports is visible in the Metals doctor, and you can find all the reports under `.metals/.reports` directory in your workspace.

## Inlay hints for worksheets
Starting from this release, inlay hints will be used to display worksheet decorations. Since a custom Metals extension to LSP was used before, this will make worksheet support available for editors that don't implement this extension (e.g. Zed). Thanks [tgodzik](https://github.com/tgodzik) for implementing this transition.

## Miscellaneous
- bugfix: correctly set `excludedPackages` on startup [harpocrates](https://github.com/harpocrates)
- bugfix: don't show incorrect docs for inner methods [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: add support for `-native` suffix in .mill-version [lolgab](https://github.com/lolgab)
- improvement: add `$` as a trigger character for completions [harpocrates](https://github.com/harpocrates)
- bugfix: completion of args in method w/ default args [harpocrates](https://github.com/harpocrates)
- bugfix: syntax for worksheet imports [btrachey](https://github.com/btrachey)
- bugfix: workaround for hover for multi declaraction in Scala 3 [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: make inlay hint resolve not dependant on `didFocus`, which is a part of Metals custom protocol and not supported by all editors [kasiaMarek](https://github.com/kasiaMarek)
- improvement: make folding regions more consistent and allow for custom setting of folding threshold [kasiaMarek](https://github.com/kasiaMarek)
- improvement: handle incorrect `scalafmtConfigPath` gracefully [Austinito](https://github.com/Austinito)
- improvement: if no mode chosen by the client, use `log` for bsp status by default [tgodzik](https://github.com/tgodzik)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.4.2..v1.5.0
    26	Tomasz Godzik
    19	Scalameta Bot
    10	kasiaMarek
     3	Alec Theriault
     2	Austinito
     1	Anton Sviridov
     1	Brian Tracey
     1	Lorenzo Gabriele
     1	dependabot[bot]
```

## Merged PRs

## [v1.4.3](https://github.com/scalameta/metals/tree/v1.4.3) (2025-01-16)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.4.2...v1.4.3)

**Merged pull requests:**

- bugfix: fix incorrect `excludedPackages` on startup
  [\#7120](https://github.com/scalameta/metals/pull/7120)
  ([harpocrates](https://github.com/harpocrates))
- fix: don't show incorrect docs for inner methods
  [\#7096](https://github.com/scalameta/metals/pull/7096)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Add `$` as a trigger character
  [\#7118](https://github.com/scalameta/metals/pull/7118)
  ([harpocrates](https://github.com/harpocrates))
- build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.15.3 to 3.16.0
  [\#7110](https://github.com/scalameta/metals/pull/7110)
  ([scalameta-bot](https://github.com/scalameta-bot))
- docs: fix syntax for worksheet imports
  [\#7113](https://github.com/scalameta/metals/pull/7113)
  ([btrachey](https://github.com/btrachey))
- chore: Add support for Scala 2.13.16
  [\#7106](https://github.com/scalameta/metals/pull/7106)
  ([tgodzik](https://github.com/tgodzik))
- Support `-native` suffix in `.mill-version`
  [\#7109](https://github.com/scalameta/metals/pull/7109)
  ([lolgab](https://github.com/lolgab))
- build(deps): Update munit from 1.0.3 to 1.0.4
  [\#7111](https://github.com/scalameta/metals/pull/7111)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.26 to 1.0.27
  [\#7101](https://github.com/scalameta/metals/pull/7101)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.23 to 2.1.24
  [\#7100](https://github.com/scalameta/metals/pull/7100)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ujson from 4.0.2 to 4.1.0
  [\#7099](https://github.com/scalameta/metals/pull/7099)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.29.2 to 4.29.3
  [\#7098](https://github.com/scalameta/metals/pull/7098)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 11.1.0 to 11.1.1
  [\#7102](https://github.com/scalameta/metals/pull/7102)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Remove pausing and window state monitoring
  [\#7097](https://github.com/scalameta/metals/pull/7097)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: completion of args in method w/ default args
  [\#7089](https://github.com/scalameta/metals/pull/7089)
  ([harpocrates](https://github.com/harpocrates))
- improvement: Don't sent text on didSave
  [\#7015](https://github.com/scalameta/metals/pull/7015)
  ([tgodzik](https://github.com/tgodzik))
- improvement: deduplicate reports 
  [\#7048](https://github.com/scalameta/metals/pull/7048)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update sbt-welcome from 0.4.0 to 0.5.0
  [\#7087](https://github.com/scalameta/metals/pull/7087)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: report QDox errors
  [\#7051](https://github.com/scalameta/metals/pull/7051)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Change the warning to be less worrying to users
  [\#7085](https://github.com/scalameta/metals/pull/7085)
  ([tgodzik](https://github.com/tgodzik))
- Refactor `validateWorkspace` to handle missing custom `scalafmtConfigPath` gracefully and log warning
  [\#7080](https://github.com/scalameta/metals/pull/7080)
  ([Austinito](https://github.com/Austinito))
- build(deps-dev): bump @types/node from 22.8.6 to 22.10.3 in /website
  [\#7081](https://github.com/scalameta/metals/pull/7081)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update coursier, ... from 2.1.22 to 2.1.23
  [\#7076](https://github.com/scalameta/metals/pull/7076)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Allow sbt BSP to run on earlier JDK
  [\#7058](https://github.com/scalameta/metals/pull/7058)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt, scripted-plugin from 1.10.6 to 1.10.7
  [\#7068](https://github.com/scalameta/metals/pull/7068)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.12.2 to 4.12.3
  [\#7069](https://github.com/scalameta/metals/pull/7069)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.12.1 to 4.12.2
  [\#7061](https://github.com/scalameta/metals/pull/7061)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Remove parallel collection since they are unused
  [\#7062](https://github.com/scalameta/metals/pull/7062)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update bloop-config from 2.1.0 to 2.2.0
  [\#7059](https://github.com/scalameta/metals/pull/7059)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.29.1 to 4.29.2
  [\#7055](https://github.com/scalameta/metals/pull/7055)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-ci-release from 1.9.0 to 1.9.2
  [\#7053](https://github.com/scalameta/metals/pull/7053)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update guava from 33.3.1-jre to 33.4.0-jre
  [\#7054](https://github.com/scalameta/metals/pull/7054)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Log message by default
  [\#7052](https://github.com/scalameta/metals/pull/7052)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update coursier, ... from 2.1.21 to 2.1.22
  [\#7057](https://github.com/scalameta/metals/pull/7057)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: scala 2 additional checks
  [\#7039](https://github.com/scalameta/metals/pull/7039)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Cross-build sbt-metals for sbt2
  [\#7045](https://github.com/scalameta/metals/pull/7045)
  ([keynmol](https://github.com/keynmol))
- fix: workaround for hover for multi declaraction (Scala 3)
  [\#7037](https://github.com/scalameta/metals/pull/7037)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix Metals for 2.13.16
  [\#7047](https://github.com/scalameta/metals/pull/7047)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Use inlay hints for worksheets
  [\#6827](https://github.com/scalameta/metals/pull/6827)
  ([tgodzik](https://github.com/tgodzik))
- chore: Fix author link after recent changes
  [\#7043](https://github.com/scalameta/metals/pull/7043)
  ([tgodzik](https://github.com/tgodzik))
- docs: Add release notes for Metals 1.4.2
  [\#7030](https://github.com/scalameta/metals/pull/7030)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update giter8 from 0.16.2 to 0.17.0
  [\#7033](https://github.com/scalameta/metals/pull/7033)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.15.2 to 3.15.3
  [\#7032](https://github.com/scalameta/metals/pull/7032)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: thresholds for folding regions
  [\#7013](https://github.com/scalameta/metals/pull/7013)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: make `inlayHints/resolve` not depend on focused document
  [\#7016](https://github.com/scalameta/metals/pull/7016)
  ([kasiaMarek](https://github.com/kasiaMarek))
