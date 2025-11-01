---
authors: tgodzik
title: Metals v1.5.2 - Strontium
---

We're happy to announce the release of Metals v1.5.2, which continues to improve
overall user experience and stability of Metals. This release includes a number
of new features, bug fixes and improvements. Especially worth mentioning is the
fix for the Scala 2 issue, which previously caused most interactive features to break when using a type defined in a package object.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">145</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">132</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">16</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">42</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">5</td>
  </tr>
</tbody>
</table>

For full details:
[https://github.com/scalameta/metals/milestone/77?closed=1](https://github.com/scalameta/metals/milestone/77?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Deduplicate compile requests](#deduplicate-compile-requests)
- [Add exact location for the test failure](#add-exact-location-for-the-test-failure)
- [Convert sbt style deps on paste in for scala-cli](#convert-sbt-style-deps-on-paste-in-for-scala-cli)
- [Add test cases discovery for TestNG](#add-test-cases-discovery-for-testng)
- [Improvements to automatic imports](#improvements-to-automatic-imports)
- [Remove Ammonite script support](#remove-ammonite-script-support)

## Deduplicate compile requests

In previous versions of Metals, we would sometimes send multiple compile
requests for the same file expecting most of them to be fast. This worked
reliably well in Bloop, but not so much in a lot of other build servers such as
sbt.

Thanks to [kasiaMarek](https://github.com/kasiaMarek) we will try not to send
multiple requests if we know a file hasn't changed between them. This should
help in a number of cases and make the experience more reliable.

## Add exact location for the test failure

Previously, when running tests in Metals with VS Code Test Explorer, the
location of the test failure was not shown, only the start of the test case.
Now, we will correctly show the exact location of the test failure, which should
make it easier to navigate to the failing test.

![test-loc](https://github.com/scalameta/gh-pages-images/blob/master/metals/2025-03-26-strontium/JuIES78.gif?raw=true)

## Convert sbt style deps on paste in for scala-cli

Thanks to [majk-p](https://github.com/majk-p) any sbt style dependencies that
you paste into a Scala CLI file after `//> using dep` will be automatically
converted to Scala CLI style dependencies. This was previously only supported in
a code action, but turns out to be useful enough to apply the rewrite automatically on paste.

![paste-dep](https://github.com/scalameta/gh-pages-images/blob/master/metals/2025-03-26-strontium/6BNvmmO.gif?raw=true)

## Add test cases discovery for TestNG

Thanks to [kasiaMarek](https://github.com/kasiaMarek) it's now possible to run
TestNG tests inside Metals, which was previously not possible.

This would mean following test cases will now show lenses or test explorer icon
next to them:

```scala
import org.testng.annotations.Test

class TestNG {
    @Test
    def testOK(): Unit = {
        assert(true)
    }
}
```

## Improvements to automatic imports

Whenever importing a particular symbol, Metals would previously suggest all the
possible symbol with the expected name. However, in a lot of cases, those symbol
might already have a method being invoked on them, which means that only symbols
containing that method should be suggested.

For example in the following code:

```scala
object O{
  Future.successful(1)
}
```

we don't need to suggest java.concurrent.Future, but only
scala.concurrent.Future since only the latter contains the `successful` method.
If none of the symbols contains that specific method, we will revert to showing
all symbols. Let us know if this is a helpful behaviour or if you see additional
cases where this could be improved.

## Remove Ammonite script support

Some time ago we have asked in
[discussions](https://github.com/scalameta/metals/discussions/6522) in our
Github repository about the possibility of removing support for Ammonite
scripts. We have not received any overwhelming feedback to keep it, so we have
decided to remove it. If you have any concerns or questions about this change,
please let us know.

The removal was directly prompted by the fact that there were serious issues
with Ammonite support with no users reporting them or anyone from maintainers
being aware of them. Which points to the fact that the feature is not widely
used.

This will also help to to ease the maintenance burden and to focus more on
stability of Metals. Scala Scripts still are and will be supported via
[Scala CLI](https://scala-cli.virtuslab.org/), so for anyone using Ammonite we suggest switching to Scala CLI. Scala CLI is the default Scala runner for Scala 3 and we believe it offers overall better experience. If you see any useful Ammonite feature missing from Scala CLI don't hesitate to start a discussion in the Scala CLI repository.

## Miscellaneous

- bugfix: Also infer type with complex expressions before method invocation
  [tgodzik](https://github.com/tgodzik)
- bugfix: fix `typeDefinition` on backticked identifier
  [harpocrates](https://github.com/harpocrates)
- bugfix: Fix when types are coming from package objects
  [tgodzik](https://github.com/tgodzik)
- bugfix: Infer arg type properly when method uses complex parameters
  [tgodzik](https://github.com/tgodzik)
- improvement: convert workspace folder to be a Metals project on chosen
  commands [kasiaMarek](https://github.com/kasiaMarek)
- Fix extracting values for fewer braces [majk-p](https://github.com/majk-p)
- bugfix: Regenerate mill BSP config on incorrect version
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Add missing completion in args in generic method with default args
  [harpocrates](https://github.com/harpocrates)
- improvement: Add file location also to stacktrace printed to stdout
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Don't propose inaccessible named arg defaults
  [harpocrates](https://github.com/harpocrates)
- feature: Support completions inside of backticks
  [harpocrates](https://github.com/harpocrates)
- bugfix: Support show decompiled and show tasty for Bazel
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Fix invalid config message when multiple scalafmt files are used with
  'include' [Sporarum](https://github.com/Sporarum)
- bugfix: don't propose inaccessible named arg defaults
  [harpocrates](https://github.com/harpocrates)
- bugfix: don't prefix scope completions from supertype
  [harpocrates](https://github.com/harpocrates)
- Add function params selection range
  [blaz-kranjc](https://github.com/blaz-kranjc)
- bugfix: Don't fail when deleting temporary files
  [tgodzik](https://github.com/tgodzik)
- bugfix: Treat self types as parent types in go to implementation context
  [KacperFKorban](https://github.com/KacperFKorban)
  [zainab-ali](https://github.com/zainab-ali)
  [kasiaMarek](https://github.com/kasiaMarek)
- fix: look for implementations in rename for possibly overridden type aliases
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Ask to start http server if not running
  ([tgodzik](https://github.com/tgodzik))
- improve error messages when no main classes can be found
  [cvogt](https://github.com/cvogt)
- fix: go to def should lead to all apply, object and class (Scala 2)
  [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Add jar search command to metals view
  [tgodzik](https://github.com/tgodzik)
- fix: generate auto-imports for more pattern completions
  ([harpocrates](https://github.com/harpocrates))
- fix: colliding pattern and scope completions
  [\#7295](https://github.com/scalameta/metals/pull/7295)
  ([harpocrates](https://github.com/harpocrates))
- improvement: add CompileTarget server command
  [cvogt](https://github.com/cvogt)
- bugfix: Try and improve credentials handling when downloading dependencies
  [tgodzik](https://github.com/tgodzik)
- Suggest open diagnostics for debug compile errors
  [cvogt](https://github.com/cvogt)
- feat: convert sbt style deps on paste in for scala-cli test.dep
  [scarf005](https://github.com/scarf005)
- bugfix: Fix issues when we would rename more symbols than needed
  [tgodzik](https://github.com/tgodzik)
- bugfix: Make sure to choose the best import option in unambiguous cases
  [tgodzik](https://github.com/tgodzik)
- improvement: Add scalafix and Scala 3 Presentation Compiler to DownloadDependencies
  [tgodzik](https://github.com/tgodzik)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.5.1..v1.5.2
    38	Tomasz Godzik
    24	Scalameta Bot
    16	scalameta-bot
    10	kasiaMarek
     7	dependabot[bot]
     6	Alec Theriault
     5	Blaz Kranjc
     4	tgodzik
     3	Christopher Vogt
     3	Katarzyna Marek
     2	Chris Birchall
     1	Francesco Nero
     1	Kacper Korban
     1	Lorenzo Gabriele
     1	Quentin Bernet
     1	Seth Tisue
     1	scarf
```

## Merged PRs

## [v1.5.2](https://github.com/scalameta/metals/tree/v1.5.2) (2025-03-18)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.5.1...v1.5.2)

**Merged pull requests:**

- bugfix: Make sure to choose the best import option
  [\#7285](https://github.com/scalameta/metals/pull/7285)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add scalafix and Scala 3 PC to DownloadDependencies
  [\#7332](https://github.com/scalameta/metals/pull/7332)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix issues when we would rename more symbols
  [\#7334](https://github.com/scalameta/metals/pull/7334)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Retry downloading dependencies using a local coursier
  [\#7330](https://github.com/scalameta/metals/pull/7330)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Forward all LSP data from BSP
  [\#7294](https://github.com/scalameta/metals/pull/7294)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 11.4.0 to 11.4.1
  [\#7335](https://github.com/scalameta/metals/pull/7335)
  ([scalameta-bot](https://github.com/scalameta-bot))
- feat: convert sbt style deps on paste in for scala-cli test.dep
  [\#7333](https://github.com/scalameta/metals/pull/7333)
  ([scarf005](https://github.com/scarf005))
- build(deps): Update guava from 33.4.0-jre to 33.4.5-jre
  [\#7324](https://github.com/scalameta/metals/pull/7324)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Suggest open diagnostics for debug compile errors
  [\#7321](https://github.com/scalameta/metals/pull/7321)
  ([cvogt](https://github.com/cvogt))
- improvement: Log when setting credentials
  [\#7329](https://github.com/scalameta/metals/pull/7329)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Don't crash the server if failed to download java semanticdb
  [\#7328](https://github.com/scalameta/metals/pull/7328)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt, scripted-plugin from 1.10.10 to 1.10.11
  [\#7326](https://github.com/scalameta/metals/pull/7326)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 1.7.0 to 1.7.1
  [\#7327](https://github.com/scalameta/metals/pull/7327)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Add credentials automatically for coursier API
  [\#7314](https://github.com/scalameta/metals/pull/7314)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update mill-contrib-testng from 0.12.8 to 0.12.9
  [\#7325](https://github.com/scalameta/metals/pull/7325)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Update Bloop to 2.0.9
  [\#7322](https://github.com/scalameta/metals/pull/7322)
  ([tgodzik](https://github.com/tgodzik))
- improvement: add CompileTarget server command
  [\#7315](https://github.com/scalameta/metals/pull/7315)
  ([cvogt](https://github.com/cvogt))
- chore: Change doctor to log less relevant data on debug
  [\#7318](https://github.com/scalameta/metals/pull/7318)
  ([tgodzik](https://github.com/tgodzik))
- chore: Bump mdoc to 2.6.5
  [\#7313](https://github.com/scalameta/metals/pull/7313)
  ([tgodzik](https://github.com/tgodzik))
- refactor: Remove Ammonite to reduce maintenance burden
  [\#7309](https://github.com/scalameta/metals/pull/7309)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 11.3.4 to 11.4.0
  [\#7305](https://github.com/scalameta/metals/pull/7305)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @babel/helpers from 7.24.7 to 7.26.10 in /website
  [\#7298](https://github.com/scalameta/metals/pull/7298)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @babel/runtime-corejs3 from 7.26.0 to 7.26.10 in /website
  [\#7299](https://github.com/scalameta/metals/pull/7299)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): Update sbt-ci-release from 1.9.2 to 1.9.3
  [\#7303](https://github.com/scalameta/metals/pull/7303)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.30.0 to 4.30.1
  [\#7304](https://github.com/scalameta/metals/pull/7304)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core from 3.9.3 to 3.9.4
  [\#7306](https://github.com/scalameta/metals/pull/7306)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump @babel/runtime from 7.18.6 to 7.26.10 in /website
  [\#7300](https://github.com/scalameta/metals/pull/7300)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- Upgrade maven-wrapper and maven
  [\#7308](https://github.com/scalameta/metals/pull/7308)
  ([cb372](https://github.com/cb372))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.13.3 to 4.13.4
  [\#7307](https://github.com/scalameta/metals/pull/7307)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: colliding pattern and scope completions
  [\#7295](https://github.com/scalameta/metals/pull/7295)
  ([harpocrates](https://github.com/harpocrates))
- fix: generate auto-imports for more pattern completions
  [\#7292](https://github.com/scalameta/metals/pull/7292)
  ([harpocrates](https://github.com/harpocrates))
- build(deps): bump prismjs from 1.29.0 to 1.30.0 in /website
  [\#7290](https://github.com/scalameta/metals/pull/7290)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- improvement: Add search command to metals view
  [\#7283](https://github.com/scalameta/metals/pull/7283)
  ([tgodzik](https://github.com/tgodzik))
- fix: go to def should lead to all apply, object and class (Scala 2)
  [\#7275](https://github.com/scalameta/metals/pull/7275)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update cli_3, scala-cli-bsp from 1.6.2 to 1.7.0
  [\#7289](https://github.com/scalameta/metals/pull/7289)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.13.2 to 4.13.3
  [\#7288](https://github.com/scalameta/metals/pull/7288)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scala3-library from 3.6.3 to 3.6.4
  [\#7287](https://github.com/scalameta/metals/pull/7287)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jsoup from 1.18.3 to 1.19.1
  [\#7280](https://github.com/scalameta/metals/pull/7280)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improve error messages when no main classes can be found
  [\#7284](https://github.com/scalameta/metals/pull/7284)
  ([cvogt](https://github.com/cvogt))
- build(deps): Update sbt, scripted-plugin from 1.10.7 to 1.10.10
  [\#7281](https://github.com/scalameta/metals/pull/7281)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update protobuf-java from 4.29.3 to 4.30.0
  [\#7278](https://github.com/scalameta/metals/pull/7278)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Only show symbol import if static method exists
  [\#7272](https://github.com/scalameta/metals/pull/7272)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalafmt-core from 3.9.2 to 3.9.3
  [\#7282](https://github.com/scalameta/metals/pull/7282)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update semanticdb-java from 0.10.3 to 0.10.4
  [\#7279](https://github.com/scalameta/metals/pull/7279)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Don't log noop compilation on info
  [\#7271](https://github.com/scalameta/metals/pull/7271)
  ([tgodzik](https://github.com/tgodzik))
- tests: Add Scala 3 tests that use symbol search
  [\#7269](https://github.com/scalameta/metals/pull/7269)
  ([tgodzik](https://github.com/tgodzik))
- build(deps-dev): bump @types/node from 22.13.0 to 22.13.8 in /website
  [\#7257](https://github.com/scalameta/metals/pull/7257)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @easyops-cn/docusaurus-search-local from 0.46.1 to 0.48.5 in
  /website [\#7258](https://github.com/scalameta/metals/pull/7258)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- bugfix: fix Scala 2 NPE in implicit inlay hints
  [\#7260](https://github.com/scalameta/metals/pull/7260)
  ([francesconero](https://github.com/francesconero))
- build(deps): Update scalafmt-core from 3.9.1 to 3.9.2
  [\#7261](https://github.com/scalameta/metals/pull/7261)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: Ask to start http server if not running
  [\#7083](https://github.com/scalameta/metals/pull/7083)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 11.3.3 to 11.3.4
  [\#7255](https://github.com/scalameta/metals/pull/7255)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scala-debug-adapter from 4.2.3 to 4.2.4
  [\#7254](https://github.com/scalameta/metals/pull/7254)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: look for implementations in rename for possibly overridden type aliases
  [\#7253](https://github.com/scalameta/metals/pull/7253)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Treat self types as parent types in go to implementation context
  [\#7170](https://github.com/scalameta/metals/pull/7170)
  ([KacperFKorban](https://github.com/KacperFKorban))
- chore: deprecate 3.3.1 and 3.3.3; delete Scala 3 pc
  [\#7243](https://github.com/scalameta/metals/pull/7243)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feat: add test cases discovery for TestNG
  [\#7200](https://github.com/scalameta/metals/pull/7200)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: when logging error report creation use `warning` instead of
  `severe` [\#7249](https://github.com/scalameta/metals/pull/7249)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Don't fail when deleting temporary files
  [\#7245](https://github.com/scalameta/metals/pull/7245)
  ([tgodzik](https://github.com/tgodzik))
- Add function parms selection range
  [\#7233](https://github.com/scalameta/metals/pull/7233)
  ([blaz-kranjc](https://github.com/blaz-kranjc))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.13.1.1 to 4.13.2
  [\#7242](https://github.com/scalameta/metals/pull/7242)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core from 3.8.6 to 3.9.1
  [\#7241](https://github.com/scalameta/metals/pull/7241)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-scalafix, scalafix-interfaces from 0.14.0 to 0.14.2
  [\#7235](https://github.com/scalameta/metals/pull/7235)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update munit from 1.0.4 to 1.1.0
  [\#7151](https://github.com/scalameta/metals/pull/7151)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 11.3.2 to 11.3.3
  [\#7237](https://github.com/scalameta/metals/pull/7237)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 1.6.1 to 1.6.2
  [\#7238](https://github.com/scalameta/metals/pull/7238)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix invalid config message when multiple files are used
  [\#7232](https://github.com/scalameta/metals/pull/7232)
  ([Sporarum](https://github.com/Sporarum))
- Add Michał to contributors
  [\#7230](https://github.com/scalameta/metals/pull/7230)
  ([tgodzik](https://github.com/tgodzik))
- docs: Add information about how to debug Scala Native
  [\#7220](https://github.com/scalameta/metals/pull/7220)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.12.7 to 4.13.1.1
  [\#7225](https://github.com/scalameta/metals/pull/7225)
  ([scalameta-bot](https://github.com/scalameta-bot))
- test: add compat for completions for Scala 2.13.17
  [\#7226](https://github.com/scalameta/metals/pull/7226)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Revert go to definition order for older scala version
  [\#7210](https://github.com/scalameta/metals/pull/7210)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 11.3.1 to 11.3.2
  [\#7223](https://github.com/scalameta/metals/pull/7223)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mill-contrib-testng from 0.12.7 to 0.12.8
  [\#7221](https://github.com/scalameta/metals/pull/7221)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update os-lib from 0.11.3 to 0.11.4
  [\#7222](https://github.com/scalameta/metals/pull/7222)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.27 to 1.0.28
  [\#7205](https://github.com/scalameta/metals/pull/7205)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update github-api from 1.326 to 1.327
  [\#7213](https://github.com/scalameta/metals/pull/7213)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc-interfaces from 2.6.3 to 2.6.4
  [\#7214](https://github.com/scalameta/metals/pull/7214)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-config from 2.3.1 to 2.3.2
  [\#7212](https://github.com/scalameta/metals/pull/7212)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Remove CI from mergify that doesn't exist
  [\#7217](https://github.com/scalameta/metals/pull/7217)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update metaconfig-core from 0.14.0 to 0.15.0
  [\#7215](https://github.com/scalameta/metals/pull/7215)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: suport show decompiled and show tasty for Bazel (build target cl…
  [\#7206](https://github.com/scalameta/metals/pull/7206)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): bump serialize-javascript from 6.0.0 to 6.0.2 in /website
  [\#7207](https://github.com/scalameta/metals/pull/7207)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- feature: completions inside of backticks
  [\#7204](https://github.com/scalameta/metals/pull/7204)
  ([harpocrates](https://github.com/harpocrates))
- feature: Add location for the test failure
  [\#7195](https://github.com/scalameta/metals/pull/7195)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Calculate indent and insert position before retrying infering…
  [\#7203](https://github.com/scalameta/metals/pull/7203)
  ([tgodzik](https://github.com/tgodzik))
- chore: Check name when renaming
  [\#7199](https://github.com/scalameta/metals/pull/7199)
  ([tgodzik](https://github.com/tgodzik))
- improvement: deduplicate compile requests
  [\#7006](https://github.com/scalameta/metals/pull/7006)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: don't prefix scope completions from supertype
  [\#7201](https://github.com/scalameta/metals/pull/7201)
  ([harpocrates](https://github.com/harpocrates))
- bugfix: don't propose inaccessible named arg defaults
  [\#7202](https://github.com/scalameta/metals/pull/7202)
  ([harpocrates](https://github.com/harpocrates))
- chore: Test Metals on JDK 21
  [\#7163](https://github.com/scalameta/metals/pull/7163)
  ([tgodzik](https://github.com/tgodzik))
- chore: Update mergify to use newest jobs
  [\#7198](https://github.com/scalameta/metals/pull/7198)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 11.3.0 to 11.3.1
  [\#7197](https://github.com/scalameta/metals/pull/7197)
  ([scalameta-bot](https://github.com/scalameta-bot))
- improvement: improve definition reports
  [\#7192](https://github.com/scalameta/metals/pull/7192)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Special handling for Mill paths in ScalaVersionSelector
  [\#7193](https://github.com/scalameta/metals/pull/7193)
  ([lolgab](https://github.com/lolgab))
- chore: Retry BreakpointScalaCliDapSuite
  [\#7173](https://github.com/scalameta/metals/pull/7173)
  ([tgodzik](https://github.com/tgodzik))
- chore: Fix issue with flakiness properly
  [\#7194](https://github.com/scalameta/metals/pull/7194)
  ([tgodzik](https://github.com/tgodzik))
- support testing on Scala 2 PR validation snapshots
  [\#7190](https://github.com/scalameta/metals/pull/7190)
  ([SethTisue](https://github.com/SethTisue))
- improvement: add file location also to stacktrace printed to stdout
  [\#7174](https://github.com/scalameta/metals/pull/7174)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Don't fail on broken pipe
  [\#7189](https://github.com/scalameta/metals/pull/7189)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Filter out cache directories containing null
  [\#7175](https://github.com/scalameta/metals/pull/7175)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: completion in args in generic method w/ default args
  [\#7182](https://github.com/scalameta/metals/pull/7182)
  ([harpocrates](https://github.com/harpocrates))
- fix: don't change focused file on `didOpen` if client is a `didFocusProvider`
  [\#7145](https://github.com/scalameta/metals/pull/7145)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: regenerate mill on incorrect version
  [\#7171](https://github.com/scalameta/metals/pull/7171)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update mdoc-interfaces from 2.6.2 to 2.6.3
  [\#7186](https://github.com/scalameta/metals/pull/7186)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update org.eclipse.lsp4j, ... from 0.23.1 to 0.24.0
  [\#7185](https://github.com/scalameta/metals/pull/7185)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mill-contrib-testng from 0.12.5 to 0.12.7
  [\#7184](https://github.com/scalameta/metals/pull/7184)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Bump all docusaurus deps
  [\#7183](https://github.com/scalameta/metals/pull/7183)
  ([tgodzik](https://github.com/tgodzik))
- build(deps-dev): bump @types/node from 22.10.3 to 22.13.0 in /website
  [\#7178](https://github.com/scalameta/metals/pull/7178)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- Convert sbt style deps on paste in for scala-cli
  [\#7176](https://github.com/scalameta/metals/pull/7176)
  ([majk-p](https://github.com/majk-p))
- build(deps): bump @docusaurus/plugin-client-redirects from 3.6.3 to 3.7.0 in
  /website [\#7181](https://github.com/scalameta/metals/pull/7181)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- chore: Don't fail supported tests in case of sonatype issues
  [\#7172](https://github.com/scalameta/metals/pull/7172)
  ([tgodzik](https://github.com/tgodzik))
- improvement: convert workspace folder to be a Metals project on chosen
  commands [\#7135](https://github.com/scalameta/metals/pull/7135)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update flyway-core from 11.2.0 to 11.3.0
  [\#7167](https://github.com/scalameta/metals/pull/7167)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix cross tests
  [\#7165](https://github.com/scalameta/metals/pull/7165)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Infer arg type properly when complex
  [\#7158](https://github.com/scalameta/metals/pull/7158)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix when types are coming from package objects
  [\#7162](https://github.com/scalameta/metals/pull/7162)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Properly print method type
  [\#7160](https://github.com/scalameta/metals/pull/7160)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: fix `typeDefinition` on backticked identifier
  [\#7119](https://github.com/scalameta/metals/pull/7119)
  ([harpocrates](https://github.com/harpocrates))
- docs: Adjust documentation to make it clearer on how to use snapshots
  [\#7103](https://github.com/scalameta/metals/pull/7103)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Also infer type with complex expressions before
  [\#7159](https://github.com/scalameta/metals/pull/7159)
  ([tgodzik](https://github.com/tgodzik))
- Fix extracting values for fewer braces
  [\#7164](https://github.com/scalameta/metals/pull/7164)
  ([majk-p](https://github.com/majk-p))
- build(deps): Update scala3-library from 3.3.4 to 3.3.5
  [\#7169](https://github.com/scalameta/metals/pull/7169)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Bump scalameta to 4.12.5
  [\#7114](https://github.com/scalameta/metals/pull/7114)
  ([tgodzik](https://github.com/tgodzik))
- docs: release notes for 1.5.1
  [\#7156](https://github.com/scalameta/metals/pull/7156)
  ([kasiaMarek](https://github.com/kasiaMarek))
