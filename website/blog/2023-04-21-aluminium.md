---
authors: kmarek
title: Metals v0.11.12 - Aluminium
---

We're happy to announce the release of Metals v0.11.12. This release brings
mostly stability fixes, some various improvements, and a start towards better
issue identification and reporting when users experience issues.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">105</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">85</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">20</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">38</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">5</td>
  </tr>
</tbody>
</table>

For full details: [https://github.com/scalameta/metals/milestone/56?closed=1]
(https://github.com/scalameta/metals/milestone/56?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Introducing Error Reports](#introducing-error-reports)
- [New code action for converting dependencies from sbt to scala-cli compliant ones](#new-code-action-for-converting-dependencies-from-sbt-to-scala-cli-compliant-ones)
- [Improvements to semantic highlighting](#improvements-to-semantic-highlighting)
- [Miscellaneous](#miscellaneous)

## Introducing Error Reports

Starting with this release, upon chosen errors or incorrect states in metals
error reports will be automatically generated and saved in the
`.metals/.reports` directory within the workspace. Such an error report can
later be used by users to attach to a github issue with additional information.
All the reports have their paths anonymised.

Currently, we create two types of reports:

- _incognito_ under `.metals/.reports/metals`: these error reports contain only
  metals related stacktraces and should not contain any sensitive/private
  information
- _unsanitized_ under `.metals/.reports/metals-full`: these error reports may
  contain come sensitive/private information (e.g. code snippets)

To make it easier to attach reports to github issues the `zip-reports` command
will zip all the **incognito** reports into `.metals/.reports/report.zip`, while
unsanitized reports will have to be browsed and attached by hand. In order not
to clutter the workspace too much, only up to 30 last reports of each kind are
kept at a time.

When running into a problem in VSCode (or any editor that implements this
command) users can use the `Metals: Open new github issue` command. This will
create a template for an issue with all basic info such as Metals version, used
build server etc.. Next, you can browse through reports and drag and drop chosen
ones to your GitHub issue. Invoking the `Metals: Zip reports` command will
create a zip of all the incognito that reports can also be included in the
issue.

![Reports](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-04-21-aluminium/wBwFjpZ.gif?raw=true)
![Zip reports](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-04-21-aluminium/YN3U3N9.gif?raw=true)

## New code action for converting dependencies from sbt to scala-cli compliant ones

New code action for scala-cli `using (dep | lib | plugin)` directives, which
allows to convert dependencies from sbt style to scala-cli compliant ones.

![Convert dependency](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-04-21-aluminium/G9W7Nox.gif?raw=true)

A great contribution by [majk-p](https://github.com/majk-p).

## Improvements to semantic highlighting

This release brings a lot of improvements for semantic highlighting thanks to
[jkciesluk](https://github.com/scalameta/metals/pulls/jkciesluk) and
[tgodzik](https://github.com/tgodzik). This includes:

- Added declaration and definition tokens.
- Parameters are now read only.
- Added semantic highlighting for using directives.
- Fixed semantic highlighting for Scala 3 worksheets.
- Changed token type for predef aliases to "class".
- Fixed sematic highlighting in sbt files.

## Miscellaneous

- bugfix: Ignored tests now show up in the test explorer.
  [kpodsiad](https://github.com/kpodsiad)
- improvement: Reworked package rename upon file move.
- bugfix: Fixed go to and hover for `TypeTest`.
  [tgodzik](https://github.com/tgodzik)
- feature: Auto connect to bazel-bsp if it's installed.
  [tanishiking](https://github.com/tanishiking)
- docs: Updated emacs support table. [kurnevsky](https://github.com/kurnevsky)
- bugfix: Placing cursor on primary contructor type parameter no longer
  incorrectly highlights type parameter with the same name used in a member
  definiton. [tgodzik](https://github.com/tgodzik)
- bugfix: Added Reload sbt after changes in the `metals.sbt` plugin file.
  [adpi2](https://github.com/adpi2)
- bugfix: Added handling fixing wildcard imports upon file rename.
- bugfix: Added refresh test case code lenses after test discovery.
  [LaurenceWarne](https://github.com/LaurenceWarne)
- bugfix: Fixed lacking newline for new imports added upon file move.
  [susliko](https://github.com/susliko)
- bugfix: Add showing lenses when BSP server is plain Scala.
- bugfix: A workaround for running BSP sbt when it's installed in a directory
  with a space on Widows. [tgodzik](https://github.com/tgodzik)
- improvement: If an aliased inferred type is not in scope dealias it.
- feature: Add hover information for structural types.
  [jkciesluk](https://github.com/jkciesluk)
- feature: Inline code action will be no longer executed if any of the
  references used on the right-hand side of the value to be inlined are shadowed
  by local definitions. In this case a warning will be shown to the user
  instead.
- bugfix: Fixed test suite discovery in presence of a companion object.
  [xydrolase](https://github.com/xydrolase)
- bugfix: Fixed shown return type of completions, that are no argument members,
  which return type depends on the ower type parameter.
- bugfix: Strip ANSI colors before printing worksheet results.
- improvement: Force close thread when file watching cancel hangs.
  [tgodzik](https://github.com/tgodzik)
- bugfix: Add end condition for tokenizing partially written code, so tokenizing
  doesn't hang. [tgodzik](https://github.com/tgodzik)
- bugfix: Correctly adjust span for extension methods for correctly displayed
  highligh. [jkciesluk](https://github.com/jkciesluk)
- bugfix: Correctly show `Expression type` (dealiased type) for parametrized
  types.
- bugfix: Filter out `-Ycheck-reentrant` option for worksheets, so worksheets
  correctly show results. [tgodzik](https://github.com/tgodzik)
- bugfix: Show correct defaults when named parameters order is mixed in Scala 2.
  [tgodzik](https://github.com/tgodzik)
- bugfix: Print better constructors in synthetic decorator.
- bugfix: Show synthetic objects as options for case classes and AnyVal implicit
  classes in `Metals Analayze Source`. [kpodsiad](https://github.com/kpodsiad)
- bugfix: Corrrectly handle testfiles renames in test explorer.
  [kpodsiad](https://github.com/kpodsiad)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.11..v0.11.12
    23	scalameta-bot
    21	Tomasz Godzik
    11	Katarzyna Marek
    10	Jakub Ciesluk
     7	Michal Pawlik
     6	Kamil Podsiadlo
     5	Rikito Taniguchi
     3	adpi2
     3	Kamil Podsiadło
     3	Michał Pawlik
     3	dependabot[bot]
     2	tgodzik
     1	Chris Kipp
     1	Vadim Chelyshov
     1	Vasiliy Morkovkin
     1	Xin Yin
     1	Laurence Warne
     1	Jędrzej Rochala
     1	Evgeny Kurnevsky
     1	Scalameta Bot
```

## Merged PRs

## [v0.11.12](https://github.com/scalameta/metals/tree/v0.11.12) (2023-04-21)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.11...v0.11.12)

**Merged pull requests:**

- build(deps): Update scalameta, semanticdb-scalac, ... from 4.7.1 to 4.7.7
  [\#5163](https://github.com/scalameta/metals/pull/5163)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier from 2.1.1 to 2.1.2
  [\#5162](https://github.com/scalameta/metals/pull/5162)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update semanticdb-java from 0.8.13 to 0.8.15
  [\#5161](https://github.com/scalameta/metals/pull/5161)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Add support for Scala 3.3.0-RC4
  [\#5158](https://github.com/scalameta/metals/pull/5158)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: mtags compilation with Java 11
  [\#5157](https://github.com/scalameta/metals/pull/5157)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Reduce IO when querying if file exists
  [\#5152](https://github.com/scalameta/metals/pull/5152)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix Scala CLI script for release notes
  [\#5151](https://github.com/scalameta/metals/pull/5151)
  ([tgodzik](https://github.com/tgodzik))
- fix: properly handle testfiles renames in Test Explorer
  [\#5042](https://github.com/scalameta/metals/pull/5042)
  ([kpodsiad](https://github.com/kpodsiad))
- Add a description about MtagsIndexer in architecture.md
  [\#4794](https://github.com/scalameta/metals/pull/4794)
  ([tanishiking](https://github.com/tanishiking))
- feature: properly index Java sources
  [\#5009](https://github.com/scalameta/metals/pull/5009)
  ([dos65](https://github.com/dos65))
- build(deps): Update coursier from 2.1.0 to 2.1.1
  [\#5142](https://github.com/scalameta/metals/pull/5142)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix test after changes in #5124
  [\#5147](https://github.com/scalameta/metals/pull/5147)
  ([tgodzik](https://github.com/tgodzik))
- chore[skip ci]: Switch nightly releases to branch with fixed test
  [\#5145](https://github.com/scalameta/metals/pull/5145)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update interface from 1.0.14 to 1.0.15
  [\#5143](https://github.com/scalameta/metals/pull/5143)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Auto spawn/connect to bazel-bsp if it's installed
  [\#5139](https://github.com/scalameta/metals/pull/5139)
  ([tanishiking](https://github.com/tanishiking))
- improvement: report on an empty hover
  [\#5128](https://github.com/scalameta/metals/pull/5128)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update flyway-core from 9.16.1 to 9.16.3
  [\#5136](https://github.com/scalameta/metals/pull/5136)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Update emacs support table
  [\#5131](https://github.com/scalameta/metals/pull/5131)
  ([kurnevsky](https://github.com/kurnevsky))
- bugfix: Show document highlight with cursor on primary cosntructor
  [\#5127](https://github.com/scalameta/metals/pull/5127)
  ([tgodzik](https://github.com/tgodzik))
- chore: Update mima last artifacts and make an explicit step in the docs
  [\#5130](https://github.com/scalameta/metals/pull/5130)
  ([tgodzik](https://github.com/tgodzik))
- Reload sbt after writing metals plugin
  [\#5126](https://github.com/scalameta/metals/pull/5126)
  ([adpi2](https://github.com/adpi2))
- bugfix: handling wildcard imports for file rename
  [\#4986](https://github.com/scalameta/metals/pull/4986)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Refresh test case code lenses after discovery
  [\#5124](https://github.com/scalameta/metals/pull/5124)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- bugfix: add newline in new imports when moving files
  [\#5120](https://github.com/scalameta/metals/pull/5120)
  ([susliko](https://github.com/susliko))
- bugfix: Show lenses when BSP server is plain Scala
  [\#5118](https://github.com/scalameta/metals/pull/5118)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix running BSP sbt when it's installed in a directory with space
  [\#5107](https://github.com/scalameta/metals/pull/5107)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Make fallback semantic tokens readonly
  [\#5067](https://github.com/scalameta/metals/pull/5067)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: Extract shared mtags without scalameta dependency
  [\#5075](https://github.com/scalameta/metals/pull/5075)
  ([rochala](https://github.com/rochala))
- build(deps): bump @docusaurus/plugin-client-redirects from 2.3.1 to 2.4.0 in
  /website [\#5111](https://github.com/scalameta/metals/pull/5111)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update xnio-nio from 3.8.8.Final to 3.8.9.Final
  [\#5113](https://github.com/scalameta/metals/pull/5113)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @docusaurus/preset-classic from 2.3.1 to 2.4.0 in /website
  [\#5109](https://github.com/scalameta/metals/pull/5109)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update interface from 1.0.13 to 1.0.14
  [\#5112](https://github.com/scalameta/metals/pull/5112)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @docusaurus/core from 2.3.1 to 2.4.0 in /website
  [\#5110](https://github.com/scalameta/metals/pull/5110)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- bugfix: Fix go to definition and hover for TypeTest
  [\#5103](https://github.com/scalameta/metals/pull/5103)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add definition and declaration properties
  [\#5047](https://github.com/scalameta/metals/pull/5047)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt-mima-plugin from 1.1.1 to 1.1.2
  [\#5104](https://github.com/scalameta/metals/pull/5104)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update versions from 0.3.1 to 0.3.2
  [\#5105](https://github.com/scalameta/metals/pull/5105)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core, scalafmt-dynamic from 3.7.2 to 3.7.3
  [\#5106](https://github.com/scalameta/metals/pull/5106)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: pkgs renames for file move
  [\#5014](https://github.com/scalameta/metals/pull/5014)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Code action to convert dependencies from sbt to mill style
  [\#5078](https://github.com/scalameta/metals/pull/5078)
  ([majk-p](https://github.com/majk-p))
- improvement: Change token type to class for predef aliases
  [\#5044](https://github.com/scalameta/metals/pull/5044)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: dealias inferred types if not in scope
  [\#5051](https://github.com/scalameta/metals/pull/5051)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix semantic highlight in sbt files
  [\#5098](https://github.com/scalameta/metals/pull/5098)
  ([tgodzik](https://github.com/tgodzik))
- feat: Hover on structural types in Scala 3
  [\#5074](https://github.com/scalameta/metals/pull/5074)
  ([jkciesluk](https://github.com/jkciesluk))
- Fix #5096: run doc on java target with semanticdb
  [\#5097](https://github.com/scalameta/metals/pull/5097)
  ([adpi2](https://github.com/adpi2))
- build(deps): Update mill-contrib-testng from 0.10.11 to 0.10.12
  [\#5081](https://github.com/scalameta/metals/pull/5081)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-jmh from 0.4.3 to 0.4.4
  [\#5094](https://github.com/scalameta/metals/pull/5094)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.20 to 0.2.1
  [\#5093](https://github.com/scalameta/metals/pull/5093)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core, scalafmt-dynamic from 3.6.1 to 3.7.2
  [\#5091](https://github.com/scalameta/metals/pull/5091)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.15.2 to 9.16.1
  [\#5090](https://github.com/scalameta/metals/pull/5090)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc, mdoc-interfaces, sbt-mdoc from 2.3.6 to 2.3.7
  [\#5089](https://github.com/scalameta/metals/pull/5089)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jol-core from 0.16 to 0.17
  [\#5088](https://github.com/scalameta/metals/pull/5088)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.15.0 to 9.15.2
  [\#5086](https://github.com/scalameta/metals/pull/5086)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jsoup from 1.15.3 to 1.15.4
  [\#5087](https://github.com/scalameta/metals/pull/5087)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update org.eclipse.lsp4j, ... from 0.20.0 to 0.20.1
  [\#5085](https://github.com/scalameta/metals/pull/5085)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier from 2.1.0-RC6 to 2.1.0
  [\#5084](https://github.com/scalameta/metals/pull/5084)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.11.0 to 3.11.1
  [\#5083](https://github.com/scalameta/metals/pull/5083)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scala-debug-adapter from 3.0.7 to 3.0.9
  [\#5080](https://github.com/scalameta/metals/pull/5080)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: use scala-cli for script instead of Ammonite
  [\#5077](https://github.com/scalameta/metals/pull/5077)
  ([ckipp01](https://github.com/ckipp01))
- feat: find companion object of AnyVal, implicit class in class finder
  [\#4974](https://github.com/scalameta/metals/pull/4974)
  ([kpodsiad](https://github.com/kpodsiad))
- docs: Switch from algolia to easyops-cn/docusaurus-search-local
  [\#5066](https://github.com/scalameta/metals/pull/5066)
  ([tanishiking](https://github.com/tanishiking))
- chore: Adjust semantic tokens tests for select dynamic in 3.3.1-RC1
  [\#5069](https://github.com/scalameta/metals/pull/5069)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: Fix select dynamic hover tests for 3.3.1-RC1
  [\#5068](https://github.com/scalameta/metals/pull/5068)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: Make semantic tokens parameters readonly
  [\#5049](https://github.com/scalameta/metals/pull/5049)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: print better constructors in synthetic decorator
  [\#5023](https://github.com/scalameta/metals/pull/5023)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feature: metals error reports
  [\#4971](https://github.com/scalameta/metals/pull/4971)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix wrong default when named params are mixed
  [\#5058](https://github.com/scalameta/metals/pull/5058)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Filter out -Ycheck-reentrant
  [\#5059](https://github.com/scalameta/metals/pull/5059)
  ([tgodzik](https://github.com/tgodzik))
- chore: use value class to for new semanticDB path type
  [\#5043](https://github.com/scalameta/metals/pull/5043)
  ([kpodsiad](https://github.com/kpodsiad))
- docs: Welcome Kasia to the team
  [\#5054](https://github.com/scalameta/metals/pull/5054)
  ([tgodzik](https://github.com/tgodzik))
- fix: Check if span in def is correct
  [\#5046](https://github.com/scalameta/metals/pull/5046)
  ([jkciesluk](https://github.com/jkciesluk))
- feat: discover Scalatest ignored tests
  [\#5035](https://github.com/scalameta/metals/pull/5035)
  ([kpodsiad](https://github.com/kpodsiad))
- improvement: Add semantic highlight for using directives
  [\#5037](https://github.com/scalameta/metals/pull/5037)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: dealias applied type params
  [\#5048](https://github.com/scalameta/metals/pull/5048)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix span for extension methods
  [\#5040](https://github.com/scalameta/metals/pull/5040)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Make sure that tokenizing doesn't hang
  [\#5050](https://github.com/scalameta/metals/pull/5050)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Force closing thread if file watching cancel hanged
  [\#5010](https://github.com/scalameta/metals/pull/5010)
  ([tgodzik](https://github.com/tgodzik))
- fix: Fix semantic tokens for worksheets in Scala 3
  [\#5038](https://github.com/scalameta/metals/pull/5038)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: strip ANSI colours before printing worksheet results
  [\#5039](https://github.com/scalameta/metals/pull/5039)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: perform `substituteTypeVars` also for methods with no args
  [\#5045](https://github.com/scalameta/metals/pull/5045)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Force update of scala-debug-adapter by Scala Steward
  [\#5032](https://github.com/scalameta/metals/pull/5032)
  ([adpi2](https://github.com/adpi2))
- bugfix: Fixed test suite discovery with the presence of companion object.
  [\#5030](https://github.com/scalameta/metals/pull/5030)
  ([xydrolase](https://github.com/xydrolase))
- docs: Link to Scala release
  [\#5022](https://github.com/scalameta/metals/pull/5022)
  ([tgodzik](https://github.com/tgodzik))
- improvement: inline value scoping
  [\#4943](https://github.com/scalameta/metals/pull/4943)
  ([kasiaMarek](https://github.com/kasiaMarek))
- docs: Add release notes for 0.11.11
  [\#4973](https://github.com/scalameta/metals/pull/4973)
  ([tgodzik](https://github.com/tgodzik))
