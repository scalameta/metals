---
authors: tgodzik
title: Metals v1.4.2 - Palladium
---

We're happy to announce the release of Metals v1.4.2, which yet again focuses on
stability, but thanks to our contributors we also have a new code action.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">93</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">71</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">13</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">17</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">1</td>
  </tr>
</tbody>
</table>

For full details:
[https://github.com/scalameta/metals/milestone/74?closed=1](https://github.com/scalameta/metals/milestone/74?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Infer method code action](#infer-method-code-action)

## Infer method code action

Thanks to great work by [ag91](https://github.com/ag91) it's now possible to ask
Metals to infer a method definition based on it's usage when symbol was not
found.

This works similar to the "Create new symbol" code action which would create a
new Scala file with the added symbol. Now, for anything starting with a lower
case we offer to create a method instead.

This will work both when the symbol is used as a parameter:

![first-example](https://github.com/scalameta/gh-pages-images/blob/master/metals/2024-12-16-palladium/9fWQ3Yg.gif?raw=true)

as well as a standalone function call:

![second-example](https://github.com/scalameta/gh-pages-images/blob/master/metals/2024-12-16-palladium/gizIjBB.gif?raw=true)

The feature has only been implemented for Scala 2 for the time being and there
might be still some unsupported cases.

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.4.1..v1.4.2
    30	Tomasz Godzik
    29	Scalameta Bot
    10	Andrea
     7	dependabot[bot]
     3	Francesco Nero
     3	kasiaMarek
     3	scarf
     2	Jean-Luc Deprez
     2	Katarzyna Marek
     1	Ruby Iris Juric
     1	nocontribute
     1	rochala
```

# Miscellaneous

- bugfix: Check if inlay hints refresh is enabled
  [tgodzik](https://github.com/tgodzik)
- bugfix: Show implicit chained calls in inlay hints
  [francesconero](https://github.com/francesconero)
- bugfix: Don't set empty edits - fixes code actions for Zed
  [tgodzik](https://github.com/tgodzik)
- bugfix: Respect customProjectRoot in BspConnector
  [spangaer](https://github.com/spangaer)
- improvement: Change default StatusBarState to LogMessage
  [tgodzik](https://github.com/tgodzik)
- bugfix: Fix issue when anonymous function name would be added as prefix
  [tgodzik](https://github.com/tgodzik)
- bugfix: Presentation Compiler is now loaded with correct classloader
  [rochala](https://github.com/rochala)
- improvement: if no binary version in jar path try using build target info
  [kasiaMarek](https://github.com/kasiaMarek)
- fix: don't look for overshadow conflicts for symbols not in the scope
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Make including detail in completion label configurable
  [Sorixelle](https://github.com/Sorixelle)
- fix: use alternative definition with stripped synthetic `filename$package.`
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: by default support `scala-cli` power options
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Update Bloop to 2.0.6 to fix importing build when using pipeling in
  sbt [tgodzik](https://github.com/tgodzik)

## Merged PRs

## [v1.4.2](https://github.com/scalameta/metals/tree/v1.4.2) (2024-12-13)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.4.1...v1.4.2)

**Merged pull requests:**

- chore: Update Bloop to 2.0.6
  [\#7029](https://github.com/scalameta/metals/pull/7029)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update coursier, ... from 2.1.20 to 2.1.21
  [\#7022](https://github.com/scalameta/metals/pull/7022)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump nanoid from 3.3.7 to 3.3.8 in /website
  [\#7020](https://github.com/scalameta/metals/pull/7020)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update mdoc-interfaces from 2.6.1 to 2.6.2
  [\#7024](https://github.com/scalameta/metals/pull/7024)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 11.0.1 to 11.1.0
  [\#7023](https://github.com/scalameta/metals/pull/7023)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update metaconfig-core from 0.13.0 to 0.14.0
  [\#7025](https://github.com/scalameta/metals/pull/7025)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.12.0 to 4.12.1
  [\#7026](https://github.com/scalameta/metals/pull/7026)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: by default support `scala-cli` power options
  [\#7017](https://github.com/scalameta/metals/pull/7017)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: use alternative with stripped synthetic `filename$package.`
  [\#7000](https://github.com/scalameta/metals/pull/7000)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update munit from 1.0.2 to 1.0.3
  [\#7011](https://github.com/scalameta/metals/pull/7011)
  ([scalameta-bot](https://github.com/scalameta-bot))
- debug: Log client config at the start
  [\#7014](https://github.com/scalameta/metals/pull/7014)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 11.0.0 to 11.0.1
  [\#7010](https://github.com/scalameta/metals/pull/7010)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.25 to 1.0.26
  [\#7009](https://github.com/scalameta/metals/pull/7009)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.19 to 2.1.20
  [\#7008](https://github.com/scalameta/metals/pull/7008)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.29.0 to 4.29.1
  [\#7007](https://github.com/scalameta/metals/pull/7007)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Make including detail in completion label configurable
  [\#6986](https://github.com/scalameta/metals/pull/6986)
  ([Sorixelle](https://github.com/Sorixelle))
- fix: don't look for overshadow conflicts for symbols not in the scope
  [\#7001](https://github.com/scalameta/metals/pull/7001)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Presentation Compiler is now loaded with correct classloader
  [\#7002](https://github.com/scalameta/metals/pull/7002)
  ([rochala](https://github.com/rochala))
- improvement: if no binary version in jar path try using build target info
  [\#6698](https://github.com/scalameta/metals/pull/6698)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update qdox from 2.1.0 to 2.2.0
  [\#6996](https://github.com/scalameta/metals/pull/6996)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @docusaurus/plugin-client-redirects from 3.6.0 to 3.6.3 in
  /website [\#6989](https://github.com/scalameta/metals/pull/6989)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update jsoup from 1.18.2 to 1.18.3
  [\#6997](https://github.com/scalameta/metals/pull/6997)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt, scripted-plugin from 1.10.5 to 1.10.6
  [\#6998](https://github.com/scalameta/metals/pull/6998)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @docusaurus/core from 3.6.0 to 3.6.3 in /website
  [\#6992](https://github.com/scalameta/metals/pull/6992)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump react-dom from 18.2.0 to 18.3.1 in /website
  [\#6991](https://github.com/scalameta/metals/pull/6991)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @easyops-cn/docusaurus-search-local from 0.45.0 to 0.46.1 in
  /website [\#6990](https://github.com/scalameta/metals/pull/6990)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @docusaurus/faster from 3.6.0 to 3.6.3 in /website
  [\#6993](https://github.com/scalameta/metals/pull/6993)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update jsoup from 1.18.1 to 1.18.2
  [\#6985](https://github.com/scalameta/metals/pull/6985)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Retry flaky tests
  [\#6987](https://github.com/scalameta/metals/pull/6987)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update interface from 1.0.24 to 1.0.25
  [\#6984](https://github.com/scalameta/metals/pull/6984)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier, ... from 2.1.18 to 2.1.19
  [\#6983](https://github.com/scalameta/metals/pull/6983)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.28.3 to 4.29.0
  [\#6981](https://github.com/scalameta/metals/pull/6981)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Add name field to distinguish different action data
  [\#6980](https://github.com/scalameta/metals/pull/6980)
  ([tgodzik](https://github.com/tgodzik))
- tests: Await program being run in native cancel test
  [\#6979](https://github.com/scalameta/metals/pull/6979)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Use codeAction/resolve for code actions
  [\#6978](https://github.com/scalameta/metals/pull/6978)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix issue when would be added as prefix
  [\#6977](https://github.com/scalameta/metals/pull/6977)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add toString to UserConfiguration
  [\#6975](https://github.com/scalameta/metals/pull/6975)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.11.2 to 4.12.0
  [\#6974](https://github.com/scalameta/metals/pull/6974)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Don't send best effort flag when disabled
  [\#6966](https://github.com/scalameta/metals/pull/6966)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 10.22.0 to 11.0.0
  [\#6973](https://github.com/scalameta/metals/pull/6973)
  ([scalameta-bot](https://github.com/scalameta-bot))
- docs: use `authors.yml`
  [\#6972](https://github.com/scalameta/metals/pull/6972)
  ([scarf005](https://github.com/scarf005))
- docs: fix broken links [\#6971](https://github.com/scalameta/metals/pull/6971)
  ([scarf005](https://github.com/scarf005))
- Infer method [\#6877](https://github.com/scalameta/metals/pull/6877)
  ([ag91](https://github.com/ag91))
- build(deps): Update coursier, ... from 2.1.17 to 2.1.18
  [\#6961](https://github.com/scalameta/metals/pull/6961)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Change default StatusBarState to LogMessage
  [\#6960](https://github.com/scalameta/metals/pull/6960)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 10.21.0 to 10.22.0
  [\#6963](https://github.com/scalameta/metals/pull/6963)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.23 to 1.0.24
  [\#6962](https://github.com/scalameta/metals/pull/6962)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.11.1 to 4.11.2
  [\#6964](https://github.com/scalameta/metals/pull/6964)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 1.5.3 to 1.5.4
  [\#6965](https://github.com/scalameta/metals/pull/6965)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix #6950:respect customProjectRoot in BspConnector
  [\#6956](https://github.com/scalameta/metals/pull/6956)
  ([spangaer](https://github.com/spangaer))
- bugfix: Try to make focused document more secure
  [\#6959](https://github.com/scalameta/metals/pull/6959)
  ([tgodzik](https://github.com/tgodzik))
- improvement: avoid atomic double touch if not needed
  [\#6957](https://github.com/scalameta/metals/pull/6957)
  ([spangaer](https://github.com/spangaer))
- bugfix: Don't set empty edits
  [\#6953](https://github.com/scalameta/metals/pull/6953)
  ([tgodzik](https://github.com/tgodzik))
- debug: Add more debug information and cancel all current state in Com…
  [\#6948](https://github.com/scalameta/metals/pull/6948)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update interface from 1.0.22 to 1.0.23
  [\#6935](https://github.com/scalameta/metals/pull/6935)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Don't ask for cancel for main and test clases
  [\#6946](https://github.com/scalameta/metals/pull/6946)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Don't show run code lense when using Main class and worksheets
  [\#6947](https://github.com/scalameta/metals/pull/6947)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.11.0 to 4.11.1
  [\#6943](https://github.com/scalameta/metals/pull/6943)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Add refresh inlay hints to true
  [\#6945](https://github.com/scalameta/metals/pull/6945)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update mill-contrib-testng from 0.11.13 to 0.12.2
  [\#6942](https://github.com/scalameta/metals/pull/6942)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump cross-spawn from 7.0.3 to 7.0.5 in /website
  [\#6941](https://github.com/scalameta/metals/pull/6941)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- bugfix: Show implicit chained calls in inlay hints
  [\#6929](https://github.com/scalameta/metals/pull/6929)
  ([francesconero](https://github.com/francesconero))
- bugfix: Check if inlay hints refresh is enabled
  [\#6940](https://github.com/scalameta/metals/pull/6940)
  ([tgodzik](https://github.com/tgodzik))
- chore: Fix wrongly pasted metals version
  [\#6939](https://github.com/scalameta/metals/pull/6939)
  ([tgodzik](https://github.com/tgodzik))
- docs: Add release notes for Metals 1.4.1
  [\#6930](https://github.com/scalameta/metals/pull/6930)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update mill-contrib-testng from 0.11.9 to 0.11.13
  [\#6934](https://github.com/scalameta/metals/pull/6934)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 1.5.1 to 1.5.3
  [\#6936](https://github.com/scalameta/metals/pull/6936)
  ([scalameta-bot](https://github.com/scalameta-bot))
