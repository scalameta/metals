---
authors: tanishiking
title: Metals v0.11.8 - Aluminium
---

We're happy to announce the release of Metals v0.11.8, bringing a number of
improvements for both Scala 2 and Scala 3.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">84</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">80</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">15</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">22</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">5</td>
  </tr>
</tbody>
</table>

For full details: https://github.com/scalameta/metals/milestone/52?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Scala 3] Auto import and completion for extension methods
- [Scala 3] Convert to Named Parameters code action
- [Scala 3] Scaladoc Completion for Scala3
- [Scala 3] Completions in string interpolation
- [Scala 2] Automatic import of types in string interpolations
- Code Action documentation
- Support of Scala 3.2.0-RC3, Scala 3.2.0-RC2

and a lot of bugfixes!

## [Scala 3] Auto import and completion for extension methods

You might know that Scala 3 has introduced `extension methods` that allow
defining new methods to your existing types.

Previously, Metals couldn't auto-complete extension methods; so developers had
to find an appropriate extension method from their workspace and manually import
it. But, this was time-consuming and not always beginner friendly.

Now, Metals provides auto-completion for extension methods and automatically
imports them!

![extension-methods](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-08-10-aluminium/EAbVHeH.gif?raw=true)

## [Scala 3] Convert to Named Parameters code action

[Metals 0.11.7 added `ConvertToNamedParameters` code action to Scala2](https://scalameta.org/metals/blog/2022/07/04/aluminium#scala-2-add-converttonamedarguments-code-action).

Thanks to the contribution by [@jkciesluk](https://github.com/jkciesluk), this
feature is now available for Scala 3!

![convert-to-named](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-08-10-aluminium/9i7MWoQ.gif?raw=true)

## [Scala 3] Scaladoc completion

Metals now supports offering Scaladoc completions in Scala 3. When typing `/**`
you get an option to auto-complete a scaladoc template for methods, classes,
etc.!

![scala-doc-completion](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-08-10-aluminium/MEJUXr3.gif?raw=true)

## [Scala 3] Completions in string interpolation

In the previous versions, whenever users wanted to include a value in a string
using string interpolation, they would need to do it all manually. Now, it is
possible to get an automatic conversion to string interpolation when typing
`$value`, as well as automatic wrapping in `{}` when accessing members of such
value.

![scala3-interpolation](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-08-10-aluminium/EyFKpiv.gif?raw=true)

## [Scala 2] Automatically import types in string interpolations

Previously, the only suggestions for string interpolations were coming from the
currently available symbols in scope. This meant that if you wanted to import
something from another package, you would need to do it manually.

This problem is now resolved. Users can easily get such symbols automatically
imported, which creates a seamless workflow.

![scala2-inteprolation](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-08-10-aluminium/cCWTQnj.gif?raw=true)

The feature is also being worked on for Scala 3.

## Code Action documentation

Have you ever wondered what kind of refactorings are available in Metals? Check
out this new page in the documentation! You can see a list of all the code
actions in Metals with examples.
https://scalameta.org/metals/docs/codeactions/codeactions

Big thanks to [zmerr](https://github.com/vzmerr) for writing this documentation.

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.7..v0.11.8
33	Tomasz Godzik
    11	Rikito Taniguchi
     9	Scala Steward
     6	jkciesluk
     6	Kamil Podsiadło
     5	vzmerr
     3	Vadim Chelyshov
     2	Adrien Piquerez
     2	scalameta-bot
     2	zmerr
     1	Arthur S
     1	Anton Sviridov
     1	tgodzik
     1	Scalameta Bot
     1	Chris Kipp
```

## Merged PRs

## [v0.11.8](https://github.com/scalameta/metals/tree/v0.11.8) (2022-08-10)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.7...v0.11.8)

**Merged pull requests:**

- [Scala 3] Revert type completions feature
  [\#4236](https://github.com/scalameta/metals/pull/4236)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Show package completions
  [\#4223](https://github.com/scalameta/metals/pull/4223)
  ([tgodzik](https://github.com/tgodzik))
- chore(ci): small changes to account for migration from LSIF -> SCIP
  [\#4222](https://github.com/scalameta/metals/pull/4222)
  ([ckipp01](https://github.com/ckipp01))
- chore: Switch to JDK 17 for most tests
  [\#4219](https://github.com/scalameta/metals/pull/4219)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Print correct method signature for Selectable
  [\#4202](https://github.com/scalameta/metals/pull/4202)
  ([tgodzik](https://github.com/tgodzik))
- Scala 3 type completion
  [\#4174](https://github.com/scalameta/metals/pull/4174)
  ([vzmerr](https://github.com/vzmerr))
- bugfix: Don't use interrupt for the Scala 3 compiler
  [\#4200](https://github.com/scalameta/metals/pull/4200)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalameta, semanticdb-scalac, ... from 4.5.9 to 4.5.11
  [\#4210](https://github.com/scalameta/metals/pull/4210)
  ([scalameta-bot](https://github.com/scalameta-bot))
- feat: Auto complete (missing) extension methods
  [\#4183](https://github.com/scalameta/metals/pull/4183)
  ([tanishiking](https://github.com/tanishiking))
- build(deps): Update mdoc, mdoc-interfaces, sbt-mdoc from 2.3.2 to 2.3.3
  [\#4209](https://github.com/scalameta/metals/pull/4209)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.0.1 to 9.0.4
  [\#4208](https://github.com/scalameta/metals/pull/4208)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Bump Bloop to latest to test out recent changes
  [\#4204](https://github.com/scalameta/metals/pull/4204)
  ([tgodzik](https://github.com/tgodzik))
- Update debug adapter to 2.2.0 stable (dependency update)
  [\#4203](https://github.com/scalameta/metals/pull/4203)
  ([arixmkii](https://github.com/arixmkii))
- fix: index inline extension methods in ScalaToplevelMtags
  [\#4199](https://github.com/scalameta/metals/pull/4199)
  ([tanishiking](https://github.com/tanishiking))
- feature: Update Ammonite runner for Scala 3 and latest Scala 2 versions
  [\#4197](https://github.com/scalameta/metals/pull/4197)
  ([tgodzik](https://github.com/tgodzik))
- chore: Support Scala 3.2.0-RC3
  [\#4198](https://github.com/scalameta/metals/pull/4198)
  ([tgodzik](https://github.com/tgodzik))
- fix: Fold the end line of template / block if it's braceless
  [\#4191](https://github.com/scalameta/metals/pull/4191)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Print local type aliases properly
  [\#4188](https://github.com/scalameta/metals/pull/4188)
  ([tgodzik](https://github.com/tgodzik))
- Add match keyword completion
  [\#4185](https://github.com/scalameta/metals/pull/4185)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: request configuration before connecting to build server
  [\#4180](https://github.com/scalameta/metals/pull/4180)
  ([dos65](https://github.com/dos65))
- Code Actions doc page [\#4157](https://github.com/scalameta/metals/pull/4157)
  ([vzmerr](https://github.com/vzmerr))
- fix: Remember choice `Don't show this again` for sbt as build server
  [\#4175](https://github.com/scalameta/metals/pull/4175)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: [Scala 3] Show correct param names in java methods
  [\#4179](https://github.com/scalameta/metals/pull/4179)
  ([tgodzik](https://github.com/tgodzik))
- fix: return all inversed dependencies in `inverseDependenciesAll`
  [\#4176](https://github.com/scalameta/metals/pull/4176)
  ([kpodsiad](https://github.com/kpodsiad))
- refactor: Use MetalsNames in ExtractValue code action
  [\#4173](https://github.com/scalameta/metals/pull/4173)
  ([tgodzik](https://github.com/tgodzik))
- feat: Import missing extension method
  [\#4141](https://github.com/scalameta/metals/pull/4141)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Use the correct RC version of 3.2.0
  [\#4171](https://github.com/scalameta/metals/pull/4171)
  ([tgodzik](https://github.com/tgodzik))
- Multiline string enhance
  [\#4168](https://github.com/scalameta/metals/pull/4168)
  ([vzmerr](https://github.com/vzmerr))
- refactor: Print better debug infor when InferredType command failed
  [\#4162](https://github.com/scalameta/metals/pull/4162)
  ([tgodzik](https://github.com/tgodzik))
- chore: use sonatypeOssRepos instead of sonatypeRepo
  [\#4169](https://github.com/scalameta/metals/pull/4169)
  ([tanishiking](https://github.com/tanishiking))
- docs: Add architecture.md
  [\#4008](https://github.com/scalameta/metals/pull/4008)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Include method signature in the label
  [\#4161](https://github.com/scalameta/metals/pull/4161)
  ([tgodzik](https://github.com/tgodzik))
- Add convertToNamedParameters support for Scala 3
  [\#4131](https://github.com/scalameta/metals/pull/4131)
  ([jkciesluk](https://github.com/jkciesluk))
- bughack: Force specific Scala 3 compiler for worksheets
  [\#4153](https://github.com/scalameta/metals/pull/4153)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Show better titles for ConvertToNamedArguments code action
  [\#4158](https://github.com/scalameta/metals/pull/4158)
  ([tgodzik](https://github.com/tgodzik))
- fix triple quoted new line on type formatting
  [\#4151](https://github.com/scalameta/metals/pull/4151)
  ([vzmerr](https://github.com/vzmerr))
- build(deps): Update flyway-core from 9.0.0 to 9.0.1
  [\#4159](https://github.com/scalameta/metals/pull/4159)
  ([scala-steward](https://github.com/scala-steward))
- bugfix: Allow completions in multiline expressions when debugging
  [\#4111](https://github.com/scalameta/metals/pull/4111)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: [Scala 2] Automatically import types in string interpolations
  [\#4140](https://github.com/scalameta/metals/pull/4140)
  ([tgodzik](https://github.com/tgodzik))
- Extend extract value with new cases
  [\#4139](https://github.com/scalameta/metals/pull/4139)
  ([jkciesluk](https://github.com/jkciesluk))
- Bump sbt-dependency-submission
  [\#4155](https://github.com/scalameta/metals/pull/4155)
  ([adpi2](https://github.com/adpi2))
- feat: Adding stub implementations for abstract given instances
  [\#4055](https://github.com/scalameta/metals/pull/4055)
  ([tanishiking](https://github.com/tanishiking))
- refactor: Show more debug messages
  [\#4150](https://github.com/scalameta/metals/pull/4150)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Reenable RenameLspSuite
  [\#4152](https://github.com/scalameta/metals/pull/4152)
  ([tgodzik](https://github.com/tgodzik))
- support for partial function
  [\#4125](https://github.com/scalameta/metals/pull/4125)
  ([zmerr](https://github.com/zmerr))
- build(deps): Update flyway-core from 8.5.13 to 9.0.0
  [\#4147](https://github.com/scalameta/metals/pull/4147)
  ([scala-steward](https://github.com/scala-steward))
- build(deps): Update sbt, scripted-plugin from 1.7.0 to 1.7.1
  [\#4148](https://github.com/scalameta/metals/pull/4148)
  ([scala-steward](https://github.com/scala-steward))
- fix: avoid duplicate migration appllication
  [\#4144](https://github.com/scalameta/metals/pull/4144)
  ([dos65](https://github.com/dos65))
- removing brace of for comprehension
  [\#4137](https://github.com/scalameta/metals/pull/4137)
  ([vzmerr](https://github.com/vzmerr))
- chore: update git-ignore-revs
  [\#4145](https://github.com/scalameta/metals/pull/4145)
  ([kpodsiad](https://github.com/kpodsiad))
- chore: change formatting of trailing commas
  [\#4050](https://github.com/scalameta/metals/pull/4050)
  ([kpodsiad](https://github.com/kpodsiad))
- build(deps): Update interface from 1.0.6 to 1.0.8
  [\#4135](https://github.com/scalameta/metals/pull/4135)
  ([scala-steward](https://github.com/scala-steward))
- build(deps): Update sbt from 1.6.2 to 1.7.0
  [\#4136](https://github.com/scalameta/metals/pull/4136)
  ([scala-steward](https://github.com/scala-steward))
- fix: Dialect should be scala21xSource3 for `-Xsource:3.x.x`
  [\#4134](https://github.com/scalameta/metals/pull/4134)
  ([tanishiking](https://github.com/tanishiking))
- chore: refactor update test cases
  [\#4129](https://github.com/scalameta/metals/pull/4129)
  ([kpodsiad](https://github.com/kpodsiad))
- bugfix: Occurence highlight did not work for local vars
  [\#4109](https://github.com/scalameta/metals/pull/4109)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Save fingerprints between restarts
  [\#4127](https://github.com/scalameta/metals/pull/4127)
  ([tgodzik](https://github.com/tgodzik))
- [docs] Update Maven integration launcher to 2.13
  [\#4130](https://github.com/scalameta/metals/pull/4130)
  ([keynmol](https://github.com/keynmol))
- feature: Add a way to turn on debug logging and fix scalafix warmup
  [\#4124](https://github.com/scalameta/metals/pull/4124)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.0 to 3.10.1
  [\#4128](https://github.com/scalameta/metals/pull/4128)
  ([scala-steward](https://github.com/scala-steward))
- java version through shell
  [\#4067](https://github.com/scalameta/metals/pull/4067)
  ([zmerr](https://github.com/zmerr))
- fix: do not start debug session for test explorer if projects contains errors
  [\#4116](https://github.com/scalameta/metals/pull/4116)
  ([kpodsiad](https://github.com/kpodsiad))
- build(deps): Update mill-contrib-testng from 0.10.4 to 0.10.5
  [\#4119](https://github.com/scalameta/metals/pull/4119)
  ([scala-steward](https://github.com/scala-steward))
- add scala 3.2.0-RC2 [\#4118](https://github.com/scalameta/metals/pull/4118)
  ([dos65](https://github.com/dos65))
- build(deps): Update jsoup from 1.15.1 to 1.15.2
  [\#4120](https://github.com/scalameta/metals/pull/4120)
  ([scala-steward](https://github.com/scala-steward))
- build(deps): Update ipcsocket from 1.4.1 to 1.5.0
  [\#4122](https://github.com/scalameta/metals/pull/4122)
  ([scala-steward](https://github.com/scala-steward))
- bugfix: [Scala 3] Improve constant and refined types printing
  [\#4117](https://github.com/scalameta/metals/pull/4117)
  ([tgodzik](https://github.com/tgodzik))
- docs: Add documentation for running scalafix in Metals
  [\#4108](https://github.com/scalameta/metals/pull/4108)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Build.sc was seen as Ammonite script
  [\#4106](https://github.com/scalameta/metals/pull/4106)
  ([jkciesluk](https://github.com/jkciesluk))
- Update sbt-dependency-graph
  [\#4110](https://github.com/scalameta/metals/pull/4110)
  ([adpi2](https://github.com/adpi2))
- bugfix: Refresh decorations even if empty to clear them out
  [\#4104](https://github.com/scalameta/metals/pull/4104)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Include entire expression in filterText for member itnerpolat…
  [\#4105](https://github.com/scalameta/metals/pull/4105)
  ([tgodzik](https://github.com/tgodzik))
- feat: ScaladocCompletion for Scala3
  [\#4076](https://github.com/scalameta/metals/pull/4076)
  ([tanishiking](https://github.com/tanishiking))
- feat: update test suite location
  [\#4073](https://github.com/scalameta/metals/pull/4073)
  ([kpodsiad](https://github.com/kpodsiad))
- feature: [Scala 3] Add interpolation completions
  [\#4061](https://github.com/scalameta/metals/pull/4061)
  ([tgodzik](https://github.com/tgodzik))
- docs: Fix wording about the expression evaluation
  [\#4101](https://github.com/scalameta/metals/pull/4101)
  ([tgodzik](https://github.com/tgodzik))
- docs: Add missing release notes section
  [\#4097](https://github.com/scalameta/metals/pull/4097)
  ([tgodzik](https://github.com/tgodzik))
- chore: Fix links in the new release docs
  [\#4096](https://github.com/scalameta/metals/pull/4096)
  ([tgodzik](https://github.com/tgodzik))
- release: Add release notes for Metals 0.11.7
  [\#4083](https://github.com/scalameta/metals/pull/4083)
  ([tgodzik](https://github.com/tgodzik))
