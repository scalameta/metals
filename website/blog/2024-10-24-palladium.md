---
authors: tgodzik
title: Metals v1.4.0 - Palladium
---

We're happy to announce the release of Metals v1.4.0, with the main focus on
introducing Bloop 2.0.0. Of course, this release also includes many
improvements.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">205</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">142</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">17</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">54</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">2</td>
  </tr>
</tbody>
</table>

For full details:
[https://github.com/scalameta/metals/milestone/72?closed=1](https://github.com/scalameta/metals/milestone/72?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Bloop 2](#bloop-2)
- [Detect custom mains](#detect-custom-mains)

## Bloop 2

Bloop is the default build server used by both Metals and Scala CLI, with the
latter also serving as the default Scala command since Scala 3.5.0. However, for
quite some time, Scala CLI was using a separate fork of Bloop, which
occasionally led to users running two build servers simultaneously. This was far
from ideal in terms of memory consumption. With Bloop 2.0.0, we have
discontinued the fork, effectively resolving this issue.

This change also means that all the improvements made by
[Alexandre Archambault](https://github.com/alexarchambault) in the separate
Bloop fork are now integrated into the main version. One of the most significant
enhancements was the switch to Unix domain sockets, which should improve the
reliability and speed of connections to the build server.

The above change has however made it necessary for us to adopt JDK 17 as the
primary JDK for running Bloop and Metals. You should still be able to compile
your code for earlier JDK versions without any issues; this will be handled by
metals when you adjust the `metals.javaHome` setting to the desired Java
version. That version is used to run the build tools and build servers, which
then will be aware of it In case of Bloop it means that if no `-release` flag or
similar is added, Bloop will add it automatically to make sure the code is
compiled correctly on a given JDK version. Similarly, Metals will add that flag
to get all the interactive features from the compiler.

The Java version used by Metals itself is managed separately and will be
automatically detected or downloaded as needed.

Metals will also attempt to shut down the old Bloop server and you might see a
notification about it. It should be perfectly safe to approve that action as the
old server will no longer be used by any tool known to us.

Metals allows users to customize the default options for the Bloop build server.
However, in most cases, it is best to use the defaults, which will be applied if
the `metals.bloopJvmOptions`setting is missing or set to an empty array.

The new default JVM options for Bloop are as follows:

```json
"metals.bloopJvmOptions" : [
      "-Xss4m",
      "-XX:MaxInlineLevel=20", // Specific option for faster C2, ignored by GraalVM
      "-XX:+UseZGC", // ZGC returns unused memory back to the OS, so Bloop does not occupy so much memory if unused
      "-XX:ZUncommitDelay=30",
      "-XX:ZCollectionInterval=5",
      "-XX:+IgnoreUnrecognizedVMOptions", // Do not fail if an non-standard (-X, -XX) VM option is not recognized.
      "-Dbloop.ignore-sig-int=true"
]
```

If you are having any issues with the build server it might be worth
experimenting with those defaults.

We also adjusted Metals default options similarly in VS Code to further minimize
the amount of memory used overall.

## Detect custom mains

Previously, we would only discover main classes defined in standard ways such as
when:

- using @main annotation for Scala3
- extending `App`
- explicit `def main(args: Array[String]): Unit` method
- scripts

However, users might actually want to create more ways of defining them such as
through compiler plugins. Turns out this is the same case we previously had with
scripts. In this release, we extended that mechanism and any unknown main
classes will be show with code lenses at the top of the file the same way as
with scripts.

## Miscellaneous

- bugfix: disambiguate workspace completions for vals in Scala 3
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: correctly handle odd formatting while indexing source jars
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Only compile current target when debugging or using scalafix
  [tgodzik](https://github.com/tgodzik)
- fix: delete test suites for deleted build targets
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: clear diagnostics in downstream targets
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Improve heuritics used for fallback symbol search
  [tgodzik](https://github.com/tgodzik)
- bugfix: use semanticdb also for local symbols and when references not
  supported in PC [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Add backticks for inferred type when needed
  [tgodzik](https://github.com/tgodzik)
- bugfix: Use default bloop properties if provided empty array
  [tgodzik](https://github.com/tgodzik)
- bugfix: Catch possible syntax errors [tgodzik](https://github.com/tgodzik)
- bugfix: Make sure best effort works with verbose
  [tgodzik](https://github.com/tgodzik)
- improvement: Don't allow empty sbtScript setting
  [tgodzik](https://github.com/tgodzik)
- Disable OrganizeImports commands if they are not available
  [wjoel](https://github.com/wjoel)
- improvement: add coursier repositories instead of overwrite
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: completions for using directive deps in test scope
  [kasiaMarek](https://github.com/kasiaMarek)
- feature: Add "opaque type" keyword completions
  [tgodzik](https://github.com/tgodzik)
- bugfix: index top level with inner when apply with `{ }` in method
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Allow -release flag for Scala 3 to get proper completions
  [tgodzik](https://github.com/tgodzik)
- fix: Get correct classpath for running debug using Mill BSP
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Include jvm opts and environment variables when running tests
  [\#6753](https://github.com/scalameta/metals/pull/6753)
  ([masonedmison](https://github.com/masonedmison))
- bugfix: Check if locally available sbt is actually a file and not a directory
  [tgodzik](https://github.com/tgodzik)
- improvement: Fallback to last successful semantic tokens instead of throwing
  to avoid VS Code reproting server as unresponsive
  [tgodzik](https://github.com/tgodzik)
- bugfix: Finer grained accessibility check for auto-imports that includes the
  private with qualifier [masonedmison](https://github.com/masonedmison)
- chore: Freeze bloop plugin for versions of sbt lower than 1.5.8
  [tgodzik](https://github.com/tgodzik)
- bugfix: Make sure Bloop is always properly restarted
  [tgodzik](https://github.com/tgodzik)
- bugfix: Exclude comma if included in symbol range for references
  [tgodzik](https://github.com/tgodzik)
- bugfix: Stop Metals from saving shared JVM indexes with XDG path violation
  [untainsYD](https://github.com/untainsYD)
- chore: Update scalafix to 0.13.0 to include Scala 3 inferred type
  [bjaglin](https://github.com/bjaglin)
- bugfix: Don't hang when checking for cache folders on Windows
  [tgodzik](https://github.com/tgodzik)
- improvement: Limit number of semanticdb files read at the same time to limit
  possibility of OOM [tgodzik](https://github.com/tgodzik)
- improvement: Basic support for new `.mill` and `.mill.scala` source files
  [lolgab](https://github.com/lolgab)
- bugfix: Fix Metals hanging on Windows [tgodzik](https://github.com/tgodzik)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.3.5..v1.4.0
    82	Tomasz Godzik
    52	Scalameta Bot
    13	Lorenzo Gabriele
    13	kasiaMarek
    10	dependabot[bot]
     8	tgodzik
     6	Mason Edmison
     3	Alexandre Archambault
     3	Anup Chenthamarakshan
     3	Katarzyna Marek
     2	Joel Wilsson
     2	Kacper Korban
     2	Yarosλaβ .
     2	nocontribute
     2	scarf
     1	Brice Jaglin
     1	Seth Tisue
```

## Merged PRs

## [v1.4.0](https://github.com/scalameta/metals/tree/v1.4.0) (2024-10-23)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.3.5...v1.4.0)

**Merged pull requests:**

- chore: Update sbt and remove sbt-launch-jar option
  [\#6876](https://github.com/scalameta/metals/pull/6876)
  ([tgodzik](https://github.com/tgodzik))
- Basic support for new `.mill` and `.mill.scala` source files
  [\#6752](https://github.com/scalameta/metals/pull/6752)
  ([lolgab](https://github.com/lolgab))
- chore: Update Scalameta to 4.11.0
  [\#6875](https://github.com/scalameta/metals/pull/6875)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Create reports directory if it doesn't exist
  [\#6874](https://github.com/scalameta/metals/pull/6874)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix not hanging on ProjectDirs
  [\#6869](https://github.com/scalameta/metals/pull/6869)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Revert sbt upgrade
  [\#6873](https://github.com/scalameta/metals/pull/6873)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt, scripted-plugin from 1.10.2 to 1.10.3
  [\#6867](https://github.com/scalameta/metals/pull/6867)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.15.0 to 3.15.1
  [\#6865](https://github.com/scalameta/metals/pull/6865)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ipcsocket from 1.6.2 to 1.6.3
  [\#6868](https://github.com/scalameta/metals/pull/6868)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.21 to 1.0.22
  [\#6866](https://github.com/scalameta/metals/pull/6866)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix Worksheet3NextSuite
  [\#6863](https://github.com/scalameta/metals/pull/6863)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Ignore Java8Suite on Mac OS
  [\#6860](https://github.com/scalameta/metals/pull/6860)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Take into account space in sbtopts
  [\#6859](https://github.com/scalameta/metals/pull/6859)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Retry code actions detection for Scala CLI
  [\#6857](https://github.com/scalameta/metals/pull/6857)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Limit number of read semanticdb files
  [\#6854](https://github.com/scalameta/metals/pull/6854)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 10.19.0 to 10.20.0
  [\#6856](https://github.com/scalameta/metals/pull/6856)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-config from 2.0.3 to 2.1.0
  [\#6855](https://github.com/scalameta/metals/pull/6855)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Update macOs since they are being discontinued
  [\#6853](https://github.com/scalameta/metals/pull/6853)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update ammonite-util from 3.0.0-M2-30-486378af to
  3.0.0-2-6342755f [\#6812](https://github.com/scalameta/metals/pull/6812)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.10.1 to 4.10.2
  [\#6846](https://github.com/scalameta/metals/pull/6846)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-ci-release from 1.7.0 to 1.8.0
  [\#6845](https://github.com/scalameta/metals/pull/6845)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 1.5.0 to 1.5.1
  [\#6818](https://github.com/scalameta/metals/pull/6818)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Do not fail while creating reports
  [\#6836](https://github.com/scalameta/metals/pull/6836)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Don't hang on ProjectDirs
  [\#6763](https://github.com/scalameta/metals/pull/6763)
  ([tgodzik](https://github.com/tgodzik))
- chore: retry bsp-error test in mac, it seems tests that use watch fil…
  [\#6844](https://github.com/scalameta/metals/pull/6844)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt-ci-release from 1.6.1 to 1.7.0
  [\#6842](https://github.com/scalameta/metals/pull/6842)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.13 to 2.1.14
  [\#6843](https://github.com/scalameta/metals/pull/6843)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Show traces and retry actions if Scala CLI fails
  [\#6838](https://github.com/scalameta/metals/pull/6838)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Increate debug start timeout so that we can retry mainClass
  [\#6837](https://github.com/scalameta/metals/pull/6837)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Try to make ending tasks more reliable
  [\#6793](https://github.com/scalameta/metals/pull/6793)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add Unreachable exception handling
  [\#6832](https://github.com/scalameta/metals/pull/6832)
  ([tgodzik](https://github.com/tgodzik))
- scalafix 0.13.0 [\#6807](https://github.com/scalameta/metals/pull/6807)
  ([bjaglin](https://github.com/bjaglin))
- chore: Removed unused values
  [\#6835](https://github.com/scalameta/metals/pull/6835)
  ([tgodzik](https://github.com/tgodzik))
- chore: Update Bloop to 2.0.3
  [\#6834](https://github.com/scalameta/metals/pull/6834)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Add null check for pc collector
  [\#6830](https://github.com/scalameta/metals/pull/6830)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalameta, semanticdb-metap from 4.9.9 to 4.10.1
  [\#6806](https://github.com/scalameta/metals/pull/6806)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Use bootstrapped compiler always
  [\#6824](https://github.com/scalameta/metals/pull/6824)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 10.18.2 to 10.19.0
  [\#6817](https://github.com/scalameta/metals/pull/6817)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ujson from 4.0.1 to 4.0.2
  [\#6813](https://github.com/scalameta/metals/pull/6813)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Metals `SqlSharedIndices.scala` XDG path violation
  [\#6802](https://github.com/scalameta/metals/pull/6802)
  ([untainsYD](https://github.com/untainsYD))
- build(deps): Update guava from 33.3.0-jre to 33.3.1-jre
  [\#6803](https://github.com/scalameta/metals/pull/6803)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 10.18.0 to 10.18.2
  [\#6805](https://github.com/scalameta/metals/pull/6805)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Avoid NPE when no severity is supplied in PublishDiagnosticsParams.Diagnostic
  [\#6799](https://github.com/scalameta/metals/pull/6799)
  ([anupc-db](https://github.com/anupc-db))
- bugfix: Exclude comma if included in symbol range
  [\#6797](https://github.com/scalameta/metals/pull/6797)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Make sure Bloop is always properly restarted
  [\#6766](https://github.com/scalameta/metals/pull/6766)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add debug information for Mill
  [\#6747](https://github.com/scalameta/metals/pull/6747)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 2.13.15
  [\#6796](https://github.com/scalameta/metals/pull/6796)
  ([tgodzik](https://github.com/tgodzik))
- chore: Freeze bloop plugin for versions of sbt lower than 1.5.8
  [\#6792](https://github.com/scalameta/metals/pull/6792)
  ([tgodzik](https://github.com/tgodzik))
- Finer grained accessibility check for auto-imports
  [\#6765](https://github.com/scalameta/metals/pull/6765)
  ([masonedmison](https://github.com/masonedmison))
- fix: Don't pass `--best-effort` flag for bazel
  [\#6786](https://github.com/scalameta/metals/pull/6786)
  ([anupc-db](https://github.com/anupc-db))
- build(deps): Update bloop-rifle from 2.0.0 to 2.0.2
  [\#6787](https://github.com/scalameta/metals/pull/6787)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc-interfaces from 2.5.4 to 2.6.1
  [\#6791](https://github.com/scalameta/metals/pull/6791)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.12 to 2.1.13
  [\#6789](https://github.com/scalameta/metals/pull/6789)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.20 to 1.0.21
  [\#6790](https://github.com/scalameta/metals/pull/6790)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.28.1 to 4.28.2
  [\#6788](https://github.com/scalameta/metals/pull/6788)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: Add a common codeAction method to the presentation compiler together
  with a supportedCodeActions method
  [\#6737](https://github.com/scalameta/metals/pull/6737)
  ([KacperFKorban](https://github.com/KacperFKorban))
- build(deps): Update munit from 1.0.1 to 1.0.2
  [\#6779](https://github.com/scalameta/metals/pull/6779)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt, scripted-plugin from 1.10.1 to 1.10.2
  [\#6778](https://github.com/scalameta/metals/pull/6778)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump express from 4.19.2 to 4.21.0 in /website
  [\#6773](https://github.com/scalameta/metals/pull/6773)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update ammonite-util from 3.0.0-M2-15-9bed9700 to
  3.0.0-M2-30-486378af [\#6768](https://github.com/scalameta/metals/pull/6768)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.28.0 to 4.28.1
  [\#6767](https://github.com/scalameta/metals/pull/6767)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 10.17.3 to 10.18.0
  [\#6771](https://github.com/scalameta/metals/pull/6771)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.10 to 2.1.12
  [\#6769](https://github.com/scalameta/metals/pull/6769)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.19 to 1.0.20
  [\#6770](https://github.com/scalameta/metals/pull/6770)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Remove old release options
  [\#6734](https://github.com/scalameta/metals/pull/6734)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Fallback to last successful tokens instead of throwing
  [\#6727](https://github.com/scalameta/metals/pull/6727)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix scala cli tests
  [\#6764](https://github.com/scalameta/metals/pull/6764)
  ([tgodzik](https://github.com/tgodzik))
- fix: do not show completions in comments
  [\#6702](https://github.com/scalameta/metals/pull/6702)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Check if locally available sbt is actually a file
  [\#6749](https://github.com/scalameta/metals/pull/6749)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Use metals Java versions as default
  [\#6721](https://github.com/scalameta/metals/pull/6721)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 10.17.2 to 10.17.3
  [\#6759](https://github.com/scalameta/metals/pull/6759)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix possible match error
  [\#6760](https://github.com/scalameta/metals/pull/6760)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scala-debug-adapter from 4.2.0 to 4.2.1
  [\#6758](https://github.com/scalameta/metals/pull/6758)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Include data arguments as part of debug parameters
  [\#6753](https://github.com/scalameta/metals/pull/6753)
  ([masonedmison](https://github.com/masonedmison))
- Support 2.12.20 [\#6751](https://github.com/scalameta/metals/pull/6751)
  ([tgodzik](https://github.com/tgodzik))
- docs: Update Bazel docs and add Mezel
  [\#6728](https://github.com/scalameta/metals/pull/6728)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Detect custom mains
  [\#6745](https://github.com/scalameta/metals/pull/6745)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Set semanticdb version correctly
  [\#6722](https://github.com/scalameta/metals/pull/6722)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Ignore single failing file
  [\#6726](https://github.com/scalameta/metals/pull/6726)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): bump clsx from 2.1.0 to 2.1.1 in /website
  [\#6739](https://github.com/scalameta/metals/pull/6739)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @docusaurus/preset-classic from 3.4.0 to 3.5.2 in /website
  [\#6742](https://github.com/scalameta/metals/pull/6742)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update protobuf-java from 4.27.4 to 4.28.0
  [\#6744](https://github.com/scalameta/metals/pull/6744)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @easyops-cn/docusaurus-search-local from 0.44.4 to 0.44.5 in
  /website [\#6743](https://github.com/scalameta/metals/pull/6743)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update protobuf-java from 4.27.3 to 4.27.4
  [\#6736](https://github.com/scalameta/metals/pull/6736)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt, scripted-plugin from 1.9.9 to 1.10.1
  [\#6608](https://github.com/scalameta/metals/pull/6608)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: get correct classpath for running debug
  [\#6715](https://github.com/scalameta/metals/pull/6715)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Allow -release flag for Scala 3
  [\#6733](https://github.com/scalameta/metals/pull/6733)
  ([tgodzik](https://github.com/tgodzik))
- fix: index top level with inner when apply with `{ }` in method
  [\#6720](https://github.com/scalameta/metals/pull/6720)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): bump webpack from 5.91.0 to 5.94.0 in /website
  [\#6731](https://github.com/scalameta/metals/pull/6731)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- feature: Add opaque type completions
  [\#6725](https://github.com/scalameta/metals/pull/6725)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): bump micromatch from 4.0.5 to 4.0.8 in /website
  [\#6719](https://github.com/scalameta/metals/pull/6719)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- docs(perf): use `swc-loader`
  [\#6706](https://github.com/scalameta/metals/pull/6706)
  ([scarf005](https://github.com/scarf005))
- build(deps): Update flyway-core from 10.17.1 to 10.17.2
  [\#6711](https://github.com/scalameta/metals/pull/6711)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: use external template chooser
  [\#6705](https://github.com/scalameta/metals/pull/6705)
  ([scarf005](https://github.com/scarf005))
- build(deps): Update cli_3, scala-cli-bsp from 1.4.3 to 1.5.0
  [\#6718](https://github.com/scalameta/metals/pull/6718)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-ci-release from 1.6.0 to 1.6.1
  [\#6707](https://github.com/scalameta/metals/pull/6707)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ujson from 4.0.0 to 4.0.1
  [\#6709](https://github.com/scalameta/metals/pull/6709)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update semanticdb-java from 0.10.2 to 0.10.3
  [\#6710](https://github.com/scalameta/metals/pull/6710)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: create `docsOut` directory
  [\#6704](https://github.com/scalameta/metals/pull/6704)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: completions for using directive deps when test scope
  [\#6701](https://github.com/scalameta/metals/pull/6701)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: use uri from params in pc for location
  [\#6692](https://github.com/scalameta/metals/pull/6692)
  ([kasiaMarek](https://github.com/kasiaMarek))
- test: remove unused compats
  [\#6697](https://github.com/scalameta/metals/pull/6697)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: add coursier repositories instead of overwrite
  [\#6696](https://github.com/scalameta/metals/pull/6696)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix typos [\#6695](https://github.com/scalameta/metals/pull/6695)
  ([SethTisue](https://github.com/SethTisue))
- improvement: Further heuristic improvements
  [\#6658](https://github.com/scalameta/metals/pull/6658)
  ([tgodzik](https://github.com/tgodzik))
- Disable OrganizeImports commands if they are not available
  [\#6595](https://github.com/scalameta/metals/pull/6595)
  ([wjoel](https://github.com/wjoel))
- improvement: Don't allow empty sbtScript setting
  [\#6687](https://github.com/scalameta/metals/pull/6687)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Make sure best effort works with verbose
  [\#6678](https://github.com/scalameta/metals/pull/6678)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Catch possible syntax errors
  [\#6681](https://github.com/scalameta/metals/pull/6681)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update guava from 33.2.1-jre to 33.3.0-jre
  [\#6689](https://github.com/scalameta/metals/pull/6689)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.22.3 to 10.17.1
  [\#6685](https://github.com/scalameta/metals/pull/6685)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update munit from 1.0.0 to 1.0.1
  [\#6690](https://github.com/scalameta/metals/pull/6690)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Rename cli to bloop-cli
  [\#6688](https://github.com/scalameta/metals/pull/6688)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Use default bloop properties if provided empty array
  [\#6679](https://github.com/scalameta/metals/pull/6679)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update h2 from 2.3.230 to 2.3.232
  [\#6684](https://github.com/scalameta/metals/pull/6684)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-rifle from 2.0.0-RC1 to 2.0.0
  [\#6682](https://github.com/scalameta/metals/pull/6682)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-ci-release from 1.5.12 to 1.6.0
  [\#6683](https://github.com/scalameta/metals/pull/6683)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Add debug messages for initializing folders
  [\#6675](https://github.com/scalameta/metals/pull/6675)
  ([tgodzik](https://github.com/tgodzik))
- test: bump scala 3 BE version in test and add `imports-for-non-compiling`
  [\#6677](https://github.com/scalameta/metals/pull/6677)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update sbt-mima-plugin from 1.1.3 to 1.1.4
  [\#6668](https://github.com/scalameta/metals/pull/6668)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump follow-redirects from 1.15.1 to 1.15.6 in /website
  [\#6674](https://github.com/scalameta/metals/pull/6674)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump braces from 3.0.2 to 3.0.3 in /website
  [\#6673](https://github.com/scalameta/metals/pull/6673)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump express from 4.18.1 to 4.19.2 in /website
  [\#6672](https://github.com/scalameta/metals/pull/6672)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- bugfix: Add yarn.lock file to improve website generation
  [\#6670](https://github.com/scalameta/metals/pull/6670)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update bloop-maven-plugin from 2.0.0 to 2.0.1
  [\#6661](https://github.com/scalameta/metals/pull/6661)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update semanticdb-java from 0.10.1 to 0.10.2
  [\#6663](https://github.com/scalameta/metals/pull/6663)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-config from 2.0.2 to 2.0.3
  [\#6660](https://github.com/scalameta/metals/pull/6660)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 1.4.1 to 1.4.3
  [\#6664](https://github.com/scalameta/metals/pull/6664)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Add backticks for inferred type when needed
  [\#6621](https://github.com/scalameta/metals/pull/6621)
  ([tgodzik](https://github.com/tgodzik))
- feature: Switch to new Bloop Rifle in the original Bloop
  [\#6532](https://github.com/scalameta/metals/pull/6532)
  ([tgodzik](https://github.com/tgodzik))
- refactor: extract connecting to build server
  [\#6597](https://github.com/scalameta/metals/pull/6597)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: use semanticdb also for local and when stale if scala version do…
  [\#6654](https://github.com/scalameta/metals/pull/6654)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: clear diagnostics in downstream targets
  [\#6604](https://github.com/scalameta/metals/pull/6604)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Improve heuritics used for fallback symbol search
  [\#6642](https://github.com/scalameta/metals/pull/6642)
  ([tgodzik](https://github.com/tgodzik))
- fix: delete test suites for deleted build targets
  [\#6652](https://github.com/scalameta/metals/pull/6652)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Specifically choose which targets to compile
  [\#6639](https://github.com/scalameta/metals/pull/6639)
  ([tgodzik](https://github.com/tgodzik))
- fix: correctly handle odd formatting while indexing
  [\#6648](https://github.com/scalameta/metals/pull/6648)
  ([kasiaMarek](https://github.com/kasiaMarek))
- chore: Add release notes for Metals 1.3.5
  [\#6647](https://github.com/scalameta/metals/pull/6647)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update semanticdb-java from 0.10.0 to 0.10.1
  [\#6650](https://github.com/scalameta/metals/pull/6650)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.27.2 to 4.27.3
  [\#6649](https://github.com/scalameta/metals/pull/6649)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: disambiguate workspace completions for vals in Scala 3
  [\#6625](https://github.com/scalameta/metals/pull/6625)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): bump @easyops-cn/docusaurus-search-local from 0.44.2 to 0.44.4 in
  /website [\#6646](https://github.com/scalameta/metals/pull/6646)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
