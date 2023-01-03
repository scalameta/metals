---
author: Tomasz Godzik
title: Metals v0.11.10 - Aluminium
authorURL: https://twitter.com/TomekGodzik
authorImageURL: https://github.com/tgodzik.png
---

We're happy to announce the release of Metals v0.11.10, which brings in a lot of
new contributions with exciting features as well as stability improvements,
which will become the primary focus over the next releases.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">249</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">195</td>
  </tr>
  <tr>
    <td>Contributors</td>
    <td align="center">21</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">83</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">8</td>
  </tr>
</tbody>
</table>

For full details:
[https://github.com/scalameta/metals/milestone/54?closed=1](https://github.com/scalameta/metals/milestone/54?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Converting sbt style to mill style dependencies](#converting-sbt-style-to-mill-style-dependencies)
- [Additional completions for dependencies](#additional-completions-for-dependencies)
- [Actionable diagnostics for scala-cli](#actionable-diagnostics-for-scala-cli)
- [Expose Test Cases as Separate Lenses](#expose-test-cases-as-separate-lenses)
- [Add support for goto type definition](#add-support-for-goto-type-definition)
- [Run main classes in terminal when using code lenses](#run-main-classes-in-terminal-when-using-code-lenses)
- [Allow rename for local names for non compiled code](#allow-rename-for-local-names-for-non-compiled-code)
- [Enable run and debug in Scala CLI scripts in Metals](#enable-run-and-debug-in-scala-cli-scripts-in-metals)
- [Refactor package definition when moving files between packages](#refactor-package-definition-when-moving-files-between-packages)
- [Scala debugger improvements](#scala-debugger-improvements)

## Converting sbt style to mill style dependencies

Thanks to [LaurenceWarne](https://github.com/LaurenceWarne) there is a new code
action that allows users to automatically convert sbt style defined dependencies
to Mill ones whenever pasting in scripts or mill files.

For example if we have an incorrect `build.sc` file:

```scala
object MyModule extends ScalaModule {
  def ivyDeps = Agg(
    "org.scalameta" %% "metals" % "1.0"
  )
}
```

Metals will now suggest to convert the incorrect line with the dependency to a
correct one when hovering or moving the cursor to its location. Afterwards to
code will turn to:

```scala
object MyModule extends ScalaModule {
  def ivyDeps = Agg(
    ivy"org.scalameta::metals:1.0"
  )
}
```

Similarly, it will also help with ammonite scripts and turn:

```scala
import $ivy."org.scalameta" %% "metals" % "1.0""
```

into:

```scala
import $ivy.`org.scalameta::metals:1.0`
```

## Additional completions for dependencies

In the previous version of Metals additional library dependency suggestions were
added in Ammonite scripts and worksheets. This version expands the number of
places where you can use this feature:

- Scala CLI scripts and files when writing `//> using lib ...`
- When writing `ivy"dependency..."` in build.sc file
- Inside sbt files when adding dependencies.

This feature allows you to explore the available libraries and their versions
without leaving the comforts of your editor.

Thanks [jkciesluk](https://github.com/jkciesluk) and
[LaurenceWarne](https://github.com/LaurenceWarne) for all the great work!

## Actionable diagnostics for scala-cli

Actionable diagnostics is a new idea within the Scala tooling ecosystem that
focuses on providing users with a potential fix for any errors or warnings that
may show up. This reduces the amount of time users will spend on fixing their
code and helps them be more productive in daily development.

The first experiment within that area was done by
[lwronski](https://github.com/lwronski) in
[Scala CLI](https://scala-cli.virtuslab.org/). When using the newest Scala CLI
you will see a warning whenever a library has a newer version available and
you'll have a quick fix suggested via code action.

![actionable](https://i.imgur.com/3D9WcQb.gif) These warnings can be disabled
within ScalaCLi using:

```
scala-cli config actions false
```

NOTE: that if you do this, you'll turn off actionable diagnostics from scala-cli
globally until this command is ran again with `true`.

There is ongoing work on adding more actionable diagnostics in the Scala 3
compiler as well, so look forward to more features like this in the future!

## Expose Test Cases as Separate Lenses

Up until the current release it was only possible to run a single test case
within Visual Studio Code and nvim-metals which use an additional Test Explorer
that is not available for all the editors. However, thanks to
([LaurenceWarne](https://github.com/LaurenceWarne)) Metals will now show lenses
to run a single test case in case of all other editors.

More details can be found in the
[PR \#4569](https://github.com/scalameta/metals/pull/4569).

## Add support for goto type definition

Users were able for a long time to navigate to the definition of a particular
symbol, which can be a method, object, class etc. After that they could
sometimes also find the definition of the type of that symbol. However, it's not
always easy and convenient to do that. From now on Metals also allows users to
use the `go to type definition` feature within all the supported editors. User
will be able to go directly to the type of a particular symbol.

![type-def](https://i.imgur.com/t77kB1S.gif)

This feature might work a bit differently depending on the symbol you are
checking:

- for methods it will show the location of the return type
- for any values, vars or parameters it will show the type of that symbol
- for classes, object or types it will only find that particular type, which
  will work the same as `go to definition`

## Run main classes in terminal when using code lenses

There are two ways of running main classes within Metals. You can decide to run
them with or without debugging with the latter stopping at the breakpoints.
These two types would both use the debug server with the first one just ignoring
breakpoints. However, setting up the whole infrastructure for debugging, when
users just want to run a main method, is adding a lot of unnecessary burden and
takes longer than if you just run it within your terminal.

In this version we introduce the change to include information about the exact
Java command to run within the code lenses, that can be used by the editors. It
currently works with VS Code and it will run main classes within the task
terminal.

![run-main](https://i.imgur.com/tXfSSj7.gif)

In next release, we also plan to use the same mechanism for running main classes
from all the other places. We also want to make tests runnable without the
debugging infrastructure, but it's a bit more complicated than for main methods.

## Allow rename for local names for non compiled code

Previously, whenever you wanted to rename anything, Metals would need to compile
your entire workspace. The reason for that was twofold. First we needed the data
from the compilation and second we wanted to make sure that will not break your
code. This however is not needed for anything that is defined in a local scope,
which can't be used outside the current file.

Metals will now allow users to rename any local code irrelevant of whether the
workspace was compiled or not.

![rename-local](https://i.imgur.com/EEKchJB.gif)

When renaming symbols that might be use outside the current file the previous
limitations will apply.

## Enable run and debug in Scala CLI scripts in Metals

Thanks to [lwronski](https://github.com/lwronski) it's now possible to run and
debug scripts when using Scala CLI.

![script-debug](https://i.imgur.com/bwfoEFY.gif)

For running scripts without debugging we will also provide the possibility of
using the explicit Java command to run from code lenses.

## Refactor package definition when moving files between packages

One of the more requested features was the ability to automatically rename
packages when moving files, which helps reduce the burden of reorganizing the
code. Thanks to some amazing works from [susliko](https://github.com/susliko)
this is now possible and should tackle most of the possible situations.

The feature will rename both the current package name as well as all the
references to it in other files.

You can see some great examples of it in the
[\#4655 PR](https://github.com/scalameta/metals/pull/4655) that introduced the
feature.

## Scala debugger improvements

Metals v0.11.10 ships a new major version of the Scala Debug Adapter, featuring
better debugging steps (step filter), conditional breakpoints, logpoints and
more. See a full list of features in the release notes of the Scala Debug
Adapter
[3.0.1](https://github.com/scalacenter/scala-debug-adapter/releases/tag/v3.0.1)
and
[3.0.3](https://github.com/scalacenter/scala-debug-adapter/releases/tag/v3.0.3).

## Miscellaneous

- bugfix: Clean diagnostics after importing a script
- bugfix: Properly show hover even if decoration spans multiple lines
- chore: Support for Scala versions 2.13.10, 3.2.1.
- bugfix: Don't backtick Scala 3 soft keywords in imports.
  [susliko](https://github.com/susliko)
- bugfix: Highlight transparent inline usages correctly.
- bugfix: Don't show case completions if already in case body.
  [jkciesluk](https://github.com/jkciesluk)
- improvement: Sort versions in reverse for coursier completions.
  [LaurenceWarne](https://github.com/LaurenceWarne)
- bugfix: [Scala 3] Properly index top level extension method to allow
  navigation and completions.
- bugfix: Don't show type parameter in outgoing calls.
  [riiswa](https://github.com/riiswa)
- bugfix: Prepend package in case of conflicts in match case completions.
  [dos65](https://github.com/dos65)
- bugfix: Fix `exhaustive match` completion when using `TypeA with TypeB` to not
  show TypeA or TypeB as possible cases.
  [jciesluk](https://github.com/jkciesluk)
- bugfix: Also rename companion when renaming renamed import.
- feat: capture and forward `diagnosticCode`.
  [ckipp01](https://github.com/ckipp01)
- bugfix: Fix go to in external classes in jars with special chars.
  [narma](https://github.com/narma)
- bugfix: add special case for auto import inside already existing to add the
  needed prefix inline. [dos65](https://github.com/dos65)
- bugfix: Fix detecting companions for classes defined directly in method for
  document highlight and rename.
- bugfix: exhaustive match when using renamed symbol.
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Retry starting Ammonite when the script file changed and was fixed.
- bugfix: Properly highlight package object name.
- bugfix: Properly highlight named arguments in case class constructors.
  [jkciesluk](https://github.com/jkciesluk)
- fix: automatically import companion object instead of apply method.
  [jkciesluk](https://github.com/jkciesluk)
- feature: Add showing semanticdb for sbt files and worksheets.
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Correctly display statuses for Ammonite scripts in Doctor.
- bugfix: Don't show string code actions when they are enclosed within selected
  range.
- bugfix: [Scala 3] Allow to automatically complete already overridden symbols.
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: [Scala 3] Make implementAll work properly with type aliases and
  higher. kind type[jkciesluk](https://github.com/jkciesluk)
- bugfix: [Scala 3] use braces for match completion with -no-indent option.
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Take into account the length of interpolator for code actions related.
  to string interpolation.
- bugfix: Make multistage Mill scripts work.
- bugfix: implement methods with path-dependent type arguments.
- bugfix: Properly reindex build after reloading sbt.
- bugfix: Recommend newer RC Scala versions when project is using a RC
  [rkrzewski](https://github.com/rkrzewski)
- chore: Try and print all dependencies used by Metals.
- bugfix: [Scala 3] Fix highlight for extension methods.
  [jkciesluk](https://github.com/jkciesluk)
- improvement: Improve behaviour of Initial ivy Completions when backticks are
  not added. [LaurenceWarne](https://github.com/LaurenceWarne)
- bugfix: Call hierarchy incoming calls includes also incoming calls of super.
  methods [kasiaMarek](https://github.com/kasiaMarek)
- feature: Show @main lenses with scala cli scripts.
  [LaurenceWarne](https://github.com/LaurenceWarne)
- bugfix: Don't encode paths on Windows to fix issues with dependencies in
  directories with non alphanumeric characters.
- bugfix: Fix document highlighting mechanism for Java files.
  [MaciejG604](https://github.com/MaciejG604)
- improvement: Better folding of definitions and overall improvements to folded
  regions. [MaciejG604](https://github.com/MaciejG604)
- bugfix: stop watching generated files to avoid compilation loop.
  [adpi2](https://github.com/adpi2)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.9..v0.11.10
    67	Tomasz Godzik
    51	scalameta-bot
    40	Jakub Ciesluk
    15	Laurence Warne
    15	Chris Kipp
     6	Adrien Piquerez
     5	kmarek
     4	Łukasz Wroński
     4	Kamil Podsiadlo
     3	Maciej Gajek
     3	Vadim Chelyshov
     2	Sergey Rublev
     2	Shardul Chiplunkar
     2	Waris Radji
     2	dependabot[bot]
     2	susliko
     1	Eugene Bulavin
     1	Rafał Krzewski
     1	Seth Tisue
     1	Vasiliy Morkovkin
     1	mattkohl-flex
```

## Merged PRs

## [v0.11.10](https://github.com/scalameta/metals/tree/v0.11.10) (2023-01-02)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.9...v0.11.10)

**Merged pull requests:**

- bugfix: Make finding filename more reliant
  [\#4784](https://github.com/scalameta/metals/pull/4784)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update ipcsocket from 1.5.0 to 1.6.1
  [\#4790](https://github.com/scalameta/metals/pull/4790)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core from 3.5.9 to 3.6.1
  [\#4791](https://github.com/scalameta/metals/pull/4791)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-dynamic from 3.5.3 to 3.5.9
  [\#4792](https://github.com/scalameta/metals/pull/4792)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core, scalafmt-dynamic from 3.5.3 to 3.5.9
  [\#4783](https://github.com/scalameta/metals/pull/4783)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Bring back previous document highlighting mechanism for Java files
  [\#4779](https://github.com/scalameta/metals/pull/4779)
  ([MaciejG604](https://github.com/MaciejG604))
- refactor: remove `compat` in tests for 2.11
  [\#4778](https://github.com/scalameta/metals/pull/4778)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: Add log when message could not be parsed
  [\#4775](https://github.com/scalameta/metals/pull/4775)
  ([tgodzik](https://github.com/tgodzik))
- Fix #4772: stop watching generated files to avoid compilation loop
  [\#4774](https://github.com/scalameta/metals/pull/4774)
  ([adpi2](https://github.com/adpi2))
- Fix nested function def not folding
  [\#4669](https://github.com/scalameta/metals/pull/4669)
  ([MaciejG604](https://github.com/MaciejG604))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.18 to 0.1.19
  [\#4771](https://github.com/scalameta/metals/pull/4771)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Add support for Scala 3.2.2-RC2
  [\#4767](https://github.com/scalameta/metals/pull/4767)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update coursier from 2.1.0-RC3-1 to 2.1.0-RC4
  [\#4762](https://github.com/scalameta/metals/pull/4762)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Fixes for the extract value feature
  [\#4725](https://github.com/scalameta/metals/pull/4725)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update flyway-core from 9.10.1 to 9.10.2
  [\#4764](https://github.com/scalameta/metals/pull/4764)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-scalac, ... from 4.7.0 to 4.7.1
  [\#4766](https://github.com/scalameta/metals/pull/4766)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.11 to 1.0.12
  [\#4763](https://github.com/scalameta/metals/pull/4763)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Set gh_token separate from github_token
  [\#4758](https://github.com/scalameta/metals/pull/4758)
  ([tgodzik](https://github.com/tgodzik))
- chore: Only use fine grained PUSH_TAG_GH_TOKEN
  [\#4755](https://github.com/scalameta/metals/pull/4755)
  ([tgodzik](https://github.com/tgodzik))
- chore: Set PUSH_TAG_GH_TOKEN for GH_TOKEN
  [\#4754](https://github.com/scalameta/metals/pull/4754)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add GH_TOKEN variable for github cli
  [\#4751](https://github.com/scalameta/metals/pull/4751)
  ([tgodzik](https://github.com/tgodzik))
- chore: Use repository scoped token for pushing tags
  [\#4750](https://github.com/scalameta/metals/pull/4750)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Don't encode paths on Windows
  [\#4748](https://github.com/scalameta/metals/pull/4748)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Update all GH_TOKEN refs to GITHUB_TOKEN
  [\#4749](https://github.com/scalameta/metals/pull/4749)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: use GITHUB_TOKEN instead, which is the default env variable
  [\#4746](https://github.com/scalameta/metals/pull/4746)
  ([tgodzik](https://github.com/tgodzik))
- chore: Explain how to write messages for commits
  [\#4726](https://github.com/scalameta/metals/pull/4726)
  ([tgodzik](https://github.com/tgodzik))
- ci: fix check_scala3_nightly
  [\#4745](https://github.com/scalameta/metals/pull/4745)
  ([dos65](https://github.com/dos65))
- chore: Bump Scalameta to 4.7.0
  [\#4732](https://github.com/scalameta/metals/pull/4732)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update coursier from 2.1.0-RC3 to 2.1.0-RC3-1
  [\#4742](https://github.com/scalameta/metals/pull/4742)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.10.0 to 9.10.1
  [\#4743](https://github.com/scalameta/metals/pull/4743)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: check for the left in diagnosticCode
  [\#4737](https://github.com/scalameta/metals/pull/4737)
  ([ckipp01](https://github.com/ckipp01))
- build(deps): Update coursier from 2.1.0-RC2 to 2.1.0-RC3
  [\#4735](https://github.com/scalameta/metals/pull/4735)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update gradle-bloop from 1.5.7-RC1 to 1.5.8
  [\#4734](https://github.com/scalameta/metals/pull/4734)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-maven-plugin from 2.0.0-RC4 to 2.0.0
  [\#4733](https://github.com/scalameta/metals/pull/4733)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Show @main lenses with scala cli scripts
  [\#4729](https://github.com/scalameta/metals/pull/4729)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- refactor: start using newly published bloop-gradle
  [\#4731](https://github.com/scalameta/metals/pull/4731)
  ([ckipp01](https://github.com/ckipp01))
- Call hierarchy incoming calls include also incoming calls of super methods
  [\#4707](https://github.com/scalameta/metals/pull/4707)
  ([kasiaMarek](https://github.com/kasiaMarek))
- refactor: use the newly published bloop-maven-plugin
  [\#4728](https://github.com/scalameta/metals/pull/4728)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Show completions in parens after newline
  [\#4722](https://github.com/scalameta/metals/pull/4722)
  ([tgodzik](https://github.com/tgodzik))
- chore: Try and print all dependencies used by Metals
  [\#4719](https://github.com/scalameta/metals/pull/4719)
  ([tgodzik](https://github.com/tgodzik))
- Improve `rename` for local symbols and worksheets
  [\#4694](https://github.com/scalameta/metals/pull/4694)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Bump Bloop to 1.5.6
  [\#4718](https://github.com/scalameta/metals/pull/4718)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.8.3 to 9.10.0
  [\#4716](https://github.com/scalameta/metals/pull/4716)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Add tests for hover on annotation
  [\#4712](https://github.com/scalameta/metals/pull/4712)
  ([tgodzik](https://github.com/tgodzik))
- fix highlight for extension methods
  [\#4705](https://github.com/scalameta/metals/pull/4705)
  ([jkciesluk](https://github.com/jkciesluk))
- Recommend newer RC Scala versions when project is using a RC
  [\#4709](https://github.com/scalameta/metals/pull/4709)
  ([rkrzewski](https://github.com/rkrzewski))
- bugfix: Properly reindex build after reloading sbt
  [\#4710](https://github.com/scalameta/metals/pull/4710)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update mill-contrib-testng from 0.10.9 to 0.10.10
  [\#4715](https://github.com/scalameta/metals/pull/4715)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix adjusting range for hovers
  [\#4713](https://github.com/scalameta/metals/pull/4713)
  ([tgodzik](https://github.com/tgodzik))
- `PresentationCompiler` returns custom `Hover`
  [\#4704](https://github.com/scalameta/metals/pull/4704)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feat: Refactor package definition when moving files between packages
  [\#4655](https://github.com/scalameta/metals/pull/4655)
  ([susliko](https://github.com/susliko))
- Improve Behaviour of Initial ivy Completions (scala 2)
  [\#4641](https://github.com/scalameta/metals/pull/4641)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- chore: Update Bloop to the newest one
  [\#4696](https://github.com/scalameta/metals/pull/4696)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.17 to 0.1.18
  [\#4693](https://github.com/scalameta/metals/pull/4693)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-config from 1.5.4-67-c910a45b to 1.5.4-78-d8126ad2
  [\#4691](https://github.com/scalameta/metals/pull/4691)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix coursier completions in ScalaCli scripts
  [\#4689](https://github.com/scalameta/metals/pull/4689)
  ([tgodzik](https://github.com/tgodzik))
- Enable run and debug Scala CLI scripts in Metals
  [\#4605](https://github.com/scalameta/metals/pull/4605)
  ([lwronski](https://github.com/lwronski))
- build(deps): bump hmarr/auto-approve-action from 2 to 3
  [\#4688](https://github.com/scalameta/metals/pull/4688)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- bugfix: Make multistage Mill scripts work
  [\#4687](https://github.com/scalameta/metals/pull/4687)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Take into account the length of interpolator
  [\#4679](https://github.com/scalameta/metals/pull/4679)
  ([tgodzik](https://github.com/tgodzik))
- exempt Scala nightlies from a test
  [\#4681](https://github.com/scalameta/metals/pull/4681)
  ([SethTisue](https://github.com/SethTisue))
- docs: Point bootstrap commands to latest release version
  [\#4680](https://github.com/scalameta/metals/pull/4680)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update bloop-config, bloop-launcher from 1.5.4-54-cfc03bdc to
  1.5.4-67-c910a45b [\#4677](https://github.com/scalameta/metals/pull/4677)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-config, bloop-launcher from 1.5.4-14-7cb43276 to
  1.5.4-54-cfc03bdc [\#4672](https://github.com/scalameta/metals/pull/4672)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: avoid newlines in json doctor output
  [\#4676](https://github.com/scalameta/metals/pull/4676)
  ([ckipp01](https://github.com/ckipp01))
- fix: implement methods with path-dependent type arguments
  [\#4674](https://github.com/scalameta/metals/pull/4674)
  ([susliko](https://github.com/susliko))
- docs: Add separate information about scripts
  [\#4643](https://github.com/scalameta/metals/pull/4643)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.8.2 to 9.8.3
  [\#4673](https://github.com/scalameta/metals/pull/4673)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: match completion with -no-indent option
  [\#4639](https://github.com/scalameta/metals/pull/4639)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update org.eclipse.lsp4j, ... from 0.18.0 to 0.19.0
  [\#4659](https://github.com/scalameta/metals/pull/4659)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Update sbt-ci-release to new org
  [\#4667](https://github.com/scalameta/metals/pull/4667)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Don't add package for worksheets and scripts
  [\#4666](https://github.com/scalameta/metals/pull/4666)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.8.1 to 9.8.2
  [\#4660](https://github.com/scalameta/metals/pull/4660)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: Don't show wildcard param completions
  [\#4665](https://github.com/scalameta/metals/pull/4665)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.4 to 3.10.5
  [\#4658](https://github.com/scalameta/metals/pull/4658)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update munit from 1.0.0-M6 to 1.0.0-M7
  [\#4661](https://github.com/scalameta/metals/pull/4661)
  ([scalameta-bot](https://github.com/scalameta-bot))
- change version in ivy completions tests
  [\#4663](https://github.com/scalameta/metals/pull/4663)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: ensure name for scala-cli is correct
  [\#4653](https://github.com/scalameta/metals/pull/4653)
  ([ckipp01](https://github.com/ckipp01))
- build(deps): Update coursier from 2.1.0-RC1 to 2.1.0-RC2
  [\#4651](https://github.com/scalameta/metals/pull/4651)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: ensure we're testing the connection with Mill
  [\#4642](https://github.com/scalameta/metals/pull/4642)
  ([ckipp01](https://github.com/ckipp01))
- chore(ci): remove coc-metals from issue template
  [\#4648](https://github.com/scalameta/metals/pull/4648)
  ([ckipp01](https://github.com/ckipp01))
- Add scala-debug-adapter 2x support by Scala CLI
  [\#4649](https://github.com/scalameta/metals/pull/4649)
  ([lwronski](https://github.com/lwronski))
- fix: prevent NPE in import missing symbol code action
  [\#4645](https://github.com/scalameta/metals/pull/4645)
  ([kpodsiad](https://github.com/kpodsiad))
- feature: Use Presentation Compiler for local rename
  [\#4542](https://github.com/scalameta/metals/pull/4542)
  ([jkciesluk](https://github.com/jkciesluk))
- feature: Add completions for plugins in ScalaCLI
  [\#4598](https://github.com/scalameta/metals/pull/4598)
  ([tgodzik](https://github.com/tgodzik))
- fix: implementAll with type aliases and higher kind type
  [\#4630](https://github.com/scalameta/metals/pull/4630)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Fix compliation issues with 3.3.x
  [\#4640](https://github.com/scalameta/metals/pull/4640)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.7.0 to 9.8.1
  [\#4637](https://github.com/scalameta/metals/pull/4637)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier from 2.1.0-M7 to 2.1.0-RC1
  [\#4632](https://github.com/scalameta/metals/pull/4632)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.16 to 0.1.17
  [\#4638](https://github.com/scalameta/metals/pull/4638)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update org.eclipse.lsp4j, ... from 0.17.0 to 0.18.0
  [\#4634](https://github.com/scalameta/metals/pull/4634)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt, scripted-plugin from 1.7.3 to 1.8.0
  [\#4635](https://github.com/scalameta/metals/pull/4635)
  ([scalameta-bot](https://github.com/scalameta-bot))
- docs: remove old resolver in docs
  [\#4627](https://github.com/scalameta/metals/pull/4627)
  ([ckipp01](https://github.com/ckipp01))
- build(deps): Update mill-contrib-testng from 0.10.8 to 0.10.9
  [\#4631](https://github.com/scalameta/metals/pull/4631)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.10 to 1.0.11
  [\#4633](https://github.com/scalameta/metals/pull/4633)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Drop support for scala-debug-adapter 1.x
  [\#4628](https://github.com/scalameta/metals/pull/4628)
  ([adpi2](https://github.com/adpi2))
- Update scala-debug-adapter to 3.0.4
  [\#4626](https://github.com/scalameta/metals/pull/4626)
  ([adpi2](https://github.com/adpi2))
- fix: complete already overridden symbols in scala 3
  [\#4621](https://github.com/scalameta/metals/pull/4621)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: more accurate signature on error in override comp
  [\#4622](https://github.com/scalameta/metals/pull/4622)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Don't show string code actions when they are enclosed within range
  [\#4608](https://github.com/scalameta/metals/pull/4608)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Correctly display statuses for Ammonite scripts in Doctor
  [\#4619](https://github.com/scalameta/metals/pull/4619)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 3.2.2-RC1
  [\#4618](https://github.com/scalameta/metals/pull/4618)
  ([tgodzik](https://github.com/tgodzik))
- add showing semanticdb for sbt files and worksheets
  [\#4616](https://github.com/scalameta/metals/pull/4616)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Update nightly release branch to use Scala 3.3.x fixes
  [\#4620](https://github.com/scalameta/metals/pull/4620)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Try to make suites less flaky
  [\#4615](https://github.com/scalameta/metals/pull/4615)
  ([tgodzik](https://github.com/tgodzik))
- Add lsp-keep-workspace-alive to emacs config
  [\#4617](https://github.com/scalameta/metals/pull/4617)
  ([shardulc](https://github.com/shardulc))
- fix: correctly import companion object for apply method
  [\#4567](https://github.com/scalameta/metals/pull/4567)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: swap scalameta Mill g8 for com-lihaoyi one
  [\#4614](https://github.com/scalameta/metals/pull/4614)
  ([ckipp01](https://github.com/ckipp01))
- chore(ci): remove Steward run from this repo
  [\#4613](https://github.com/scalameta/metals/pull/4613)
  ([ckipp01](https://github.com/ckipp01))
- fix: don't show incorrect `extension` keyword completion
  [\#4607](https://github.com/scalameta/metals/pull/4607)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Highlight for named arg in case class constructors
  [\#4599](https://github.com/scalameta/metals/pull/4599)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Default to empty jvm environment instead of failing
  [\#4611](https://github.com/scalameta/metals/pull/4611)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix compilation issues with Scala 3.3.x
  [\#4612](https://github.com/scalameta/metals/pull/4612)
  ([tgodzik](https://github.com/tgodzik))
- chore(deps): bump millw to 0.4.4
  [\#4609](https://github.com/scalameta/metals/pull/4609)
  ([ckipp01](https://github.com/ckipp01))
- feature: Add shell command required to run main classes
  [\#4566](https://github.com/scalameta/metals/pull/4566)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.6.0 to 9.7.0
  [\#4603](https://github.com/scalameta/metals/pull/4603)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix wrong namePos on package objects
  [\#4602](https://github.com/scalameta/metals/pull/4602)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt, scripted-plugin from 1.7.2 to 1.7.3
  [\#4604](https://github.com/scalameta/metals/pull/4604)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Ignore failing tests on Java 8
  [\#4601](https://github.com/scalameta/metals/pull/4601)
  ([tgodzik](https://github.com/tgodzik))
- refactor: change where we stick millw
  [\#4586](https://github.com/scalameta/metals/pull/4586)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Deduplicate coursier completions
  [\#4597](https://github.com/scalameta/metals/pull/4597)
  ([tgodzik](https://github.com/tgodzik))
- Workaround fix for double escaping % for jar URIs
  [\#4595](https://github.com/scalameta/metals/pull/4595)
  ([narma](https://github.com/narma))
- bugfix: Retry starting Ammonite when file changed
  [\#4582](https://github.com/scalameta/metals/pull/4582)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 3.2.1
  [\#4592](https://github.com/scalameta/metals/pull/4592)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Check if client returned null in configuration
  [\#4588](https://github.com/scalameta/metals/pull/4588)
  ([meadofpoetry](https://github.com/meadofpoetry))
- Refactor override completions, move auto import generator
  [\#4590](https://github.com/scalameta/metals/pull/4590)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: exhaustive match on renamed symbol
  [\#4589](https://github.com/scalameta/metals/pull/4589)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: get rid of resolver warning on startup
  [\#4587](https://github.com/scalameta/metals/pull/4587)
  ([ckipp01](https://github.com/ckipp01))
- build(deps): bump coursier/setup-action from 1.2.1 to 1.3.0
  [\#4583](https://github.com/scalameta/metals/pull/4583)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- bugfix: Handle null being sent in the user configuration
  [\#4580](https://github.com/scalameta/metals/pull/4580)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update interface from 1.0.9 to 1.0.10
  [\#4579](https://github.com/scalameta/metals/pull/4579)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: Move some logic from CompletionProvider
  [\#4572](https://github.com/scalameta/metals/pull/4572)
  ([tgodzik](https://github.com/tgodzik))
- Fix Test Case Code Lenses Appearing with Test Explorer
  [\#4578](https://github.com/scalameta/metals/pull/4578)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- bump ivy dep version in completions tests
  [\#4576](https://github.com/scalameta/metals/pull/4576)
  ([jkciesluk](https://github.com/jkciesluk))
- feature: Add support for type definition
  [\#4507](https://github.com/scalameta/metals/pull/4507)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.5.1 to 9.6.0
  [\#4575](https://github.com/scalameta/metals/pull/4575)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix detecting companions for classes defined directly in method
  [\#4573](https://github.com/scalameta/metals/pull/4573)
  ([tgodzik](https://github.com/tgodzik))
- Expose Test Cases as Separate Lenses
  [\#4569](https://github.com/scalameta/metals/pull/4569)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- Fix go to in external classes in jars with special chars
  [\#4560](https://github.com/scalameta/metals/pull/4560)
  ([narma](https://github.com/narma))
- feat: capture and forward `diagnosticCode`
  [\#4239](https://github.com/scalameta/metals/pull/4239)
  ([ckipp01](https://github.com/ckipp01))
- Use new scala-debug-adapter 3
  [\#4565](https://github.com/scalameta/metals/pull/4565)
  ([adpi2](https://github.com/adpi2))
- bugfix: Also rename companion when renaming renamed import
  [\#4556](https://github.com/scalameta/metals/pull/4556)
  ([tgodzik](https://github.com/tgodzik))
- fix: add special case for autoimport inside Import tree
  [\#4555](https://github.com/scalameta/metals/pull/4555)
  ([dos65](https://github.com/dos65))
- Add Ammonite Ivy completions for Scala 3
  [\#4508](https://github.com/scalameta/metals/pull/4508)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update org.eclipse.lsp4j, ... from 0.15.0 to 0.17.0
  [\#4552](https://github.com/scalameta/metals/pull/4552)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.4.0 to 9.5.1
  [\#4553](https://github.com/scalameta/metals/pull/4553)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Fix #4532: catch ZipException when opening jar files
  [\#4550](https://github.com/scalameta/metals/pull/4550)
  ([adpi2](https://github.com/adpi2))
- bugfix: Try to fix flaky ScalaCLI test
  [\#4519](https://github.com/scalameta/metals/pull/4519)
  ([tgodzik](https://github.com/tgodzik))
- Scala cli actionable diagnostic
  [\#4297](https://github.com/scalameta/metals/pull/4297)
  ([lwronski](https://github.com/lwronski))
- Fix `exhaustive match` completion for type `TypeA with TypeB`
  [\#4547](https://github.com/scalameta/metals/pull/4547)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Add support for Scala 3.2.1-RC3 and 3.2.1-RC4
  [\#4549](https://github.com/scalameta/metals/pull/4549)
  ([tgodzik](https://github.com/tgodzik))
- chore: Update default Scala 2.13 version for Ammonite
  [\#4525](https://github.com/scalameta/metals/pull/4525)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Set connecting-scalacli test as flaky
  [\#4546](https://github.com/scalameta/metals/pull/4546)
  ([tgodzik](https://github.com/tgodzik))
- fix: scala3 match-case completions - improve autoimports
  [\#4397](https://github.com/scalameta/metals/pull/4397)
  ([dos65](https://github.com/dos65))
- Add sbt lib completions
  [\#4496](https://github.com/scalameta/metals/pull/4496)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update scalafix-interfaces from 0.10.3 to 0.10.4
  [\#4538](https://github.com/scalameta/metals/pull/4538)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: [Scala 3] Properly prepend package to toplevel methods
  [\#4544](https://github.com/scalameta/metals/pull/4544)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update ammonite-util from 2.5.4-35-ebdeebe4 to 2.5.5
  [\#4539](https://github.com/scalameta/metals/pull/4539)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Documentation fixes [\#4543](https://github.com/scalameta/metals/pull/4543)
  ([mattkohl-flex](https://github.com/mattkohl-flex))
- Add mill ivy completions
  [\#4497](https://github.com/scalameta/metals/pull/4497)
  ([jkciesluk](https://github.com/jkciesluk))
- Sort versions in reverse for coursier completions
  [\#4536](https://github.com/scalameta/metals/pull/4536)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.3 to 3.10.4
  [\#4540](https://github.com/scalameta/metals/pull/4540)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Highlight transparent inline usages correctly
  [\#4529](https://github.com/scalameta/metals/pull/4529)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.15 to 0.1.16
  [\#4541](https://github.com/scalameta/metals/pull/4541)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Remove to check for Scala versions in worksheets
  [\#4535](https://github.com/scalameta/metals/pull/4535)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Try to download mtags for latest supported nightly Scala…
  [\#4511](https://github.com/scalameta/metals/pull/4511)
  ([tgodzik](https://github.com/tgodzik))
- Update bsp4j to 2.1.0-M3
  [\#4527](https://github.com/scalameta/metals/pull/4527)
  ([lwronski](https://github.com/lwronski))
- fix: incorrect case completion position
  [\#4517](https://github.com/scalameta/metals/pull/4517)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Revert undertow and ignore
  [\#4526](https://github.com/scalameta/metals/pull/4526)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update ammonite-util from 2.5.4-34-1c7b3c38 to 2.5.4-35-ebdeebe4
  [\#4521](https://github.com/scalameta/metals/pull/4521)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update undertow-core from 2.2.20.Final to 2.3.0.Final
  [\#4524](https://github.com/scalameta/metals/pull/4524)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-scalafix, scalafix-interfaces from 0.10.3 to 0.10.4
  [\#4520](https://github.com/scalameta/metals/pull/4520)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mill-contrib-testng from 0.10.7 to 0.10.8
  [\#4522](https://github.com/scalameta/metals/pull/4522)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update qdox from 2.0.2 to 2.0.3
  [\#4523](https://github.com/scalameta/metals/pull/4523)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: Fix compiler warnings
  [\#4516](https://github.com/scalameta/metals/pull/4516)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Filter out `<local child>` symbol for exhaustive matches
  [\#4514](https://github.com/scalameta/metals/pull/4514)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Wrap `isPublic` in try
  [\#4513](https://github.com/scalameta/metals/pull/4513)
  ([tgodzik](https://github.com/tgodzik))
- feature: Add links for easier navigation in the blogpost
  [\#4512](https://github.com/scalameta/metals/pull/4512)
  ([tgodzik](https://github.com/tgodzik))
- Fix show declaration type on outgoing calls request
  [\#4490](https://github.com/scalameta/metals/pull/4490)
  ([riiswa](https://github.com/riiswa))
- Update User Config Options Doc
  [\#4506](https://github.com/scalameta/metals/pull/4506)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- chore: Fix issues within the community suite for Scala 2
  [\#4509](https://github.com/scalameta/metals/pull/4509)
  ([tgodzik](https://github.com/tgodzik))
- fix: soft-keyword-matching autoimports in Scala 3
  [\#4491](https://github.com/scalameta/metals/pull/4491)
  ([susliko](https://github.com/susliko))
- chore: Add support for Scala 2.13.10
  [\#4503](https://github.com/scalameta/metals/pull/4503)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update undertow-core from 2.2.19.Final to 2.2.20.Final
  [\#4504](https://github.com/scalameta/metals/pull/4504)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: Move calculating description, kind and label to CompletionValue
  [\#4500](https://github.com/scalameta/metals/pull/4500)
  ([tgodzik](https://github.com/tgodzik))
- Show Scala completions first in ScalaCli using directives
  [\#4499](https://github.com/scalameta/metals/pull/4499)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Properly extract multiline ranges
  [\#4501](https://github.com/scalameta/metals/pull/4501)
  ([tgodzik](https://github.com/tgodzik))
- feature: Connect automatically when `.bsp` folder shows up
  [\#4486](https://github.com/scalameta/metals/pull/4486)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update org.eclipse.lsp4j, ... from 0.15.0 to 0.16.0
  [\#4493](https://github.com/scalameta/metals/pull/4493)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Welcome Jakub to the team
  [\#4485](https://github.com/scalameta/metals/pull/4485)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt, scripted-plugin from 1.7.1 to 1.7.2
  [\#4494](https://github.com/scalameta/metals/pull/4494)
  ([scalameta-bot](https://github.com/scalameta-bot))
- feature: Add completions for `//> using lib @@` in Scala CLI
  [\#4417](https://github.com/scalameta/metals/pull/4417)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Clean diagnostics after importing a script
  [\#4483](https://github.com/scalameta/metals/pull/4483)
  ([tgodzik](https://github.com/tgodzik))
- Proposal: New Code Action for Converting sbt Style to mill style Deps
  [\#4465](https://github.com/scalameta/metals/pull/4465)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- chore: Add release notes for v0.11.9
  [\#4469](https://github.com/scalameta/metals/pull/4469)
  ([tgodzik](https://github.com/tgodzik))
