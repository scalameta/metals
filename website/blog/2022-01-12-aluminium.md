---
authors: tgodzik
title: Metals v0.11.0 - Aluminium
---

We're happy to announce the release of Metals v0.11.0! Due to the number of
changes in this release we decided to introduce one more minor version before
Metals 1.0.0, which is planned to come this year.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">337</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">136</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">11</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">42</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">11</td>
  </tr>
</tbody>
</table>

For full details: https://github.com/scalameta/metals/milestone/45?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs,
Sublime Text and Eclipse. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from [Lunatech](https://lunatech.com) along with contributors from
the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Add CFR class file viewer.
- [Scala 3] Type annotations on code selection.
- Simplify usage of sbt server.
- Better support for mill BSP.
- Extract value code action.
- Add command to run current file.
- Support test explorer API.
- Add go to definition via links for synthetic decorations.
- Basic Java support.
- Add support for Scala 2.13.8.
- Reworked Metals Doctor.

## Add CFR class file viewer

In addition to the recently added semanticdb, tasty and javap functionalities,
thanks to the efforts of [Arthurm1](https://github.com/Arthurm1), it's now
possible to decompile class files using Lee Benfield's
[CFR (Class File Reader)](https://www.benf.org/other/cfr/faq.html).

CFR decompiles class files to java, which is much more readable than using
`javap`.

![cfr](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-01-12-aluminium/exDFGyT.gif?raw=true)

In the future this might help in go to definition for jars that do not have a
corresponding source jar, which is what Metals uses today.

Editor support: VS Code, nvim-metals, Sublime Text

**Extension authors:**

This can be added as a new command for the users to invoke.

## [Scala 3] Type annotations on code selection

In one of the last releases
[v0.10.8](https://scalameta.org/metals/blog/2021/10/26/tungsten#type-annotations-on-code-selection)
we added the ability to inspect the type of an expression that is currently
selected. We are happy to announce that this functionality is now also available
for Scala 3, which is thanks to the continued effort of the team to bring the
users the best Scala 3 experience possible.

Editor support: VS Code, nvim-metals, Sublime Text

## Simplify usage of sbt server

Previously if you wanted to use sbt as your build server instead of the default
Bloop you'd need to ensure a .bsp/sbt.json file existed before you tried to
switch your build server. We originally created the metals.generate-bsp command
for this, but realized this potentially forced extra steps on the user. To make
this simpler, now even if no BSP entry exists for sbt and you attempt to switch
your build server to sbt via the metals.bsp-switch command, we'll either use the
existing .bsp/sbt.json file if one exists or automatically generate it (needs at
least sbt 1.4.1) for you and then switch to it.

Editor support: All.

## Better support for mill BSP

Recently [Mill](https://github.com/com-lihaoyi/mill) has complete revamped their
BSP implementation allowing for a much better experience for Metals users. Just
like sbt, you can now use the `metals.bsp-switch` command to use Mill as your
BSP server (needs at least Mill 0.10.0-M4). Keep in mind that the BSP support in
Mill is still a work-in-progress. If you are interested in Mill BSP and you're a
Metals user, feel free to include your input
[here](https://github.com/com-lihaoyi/mill/issues/1546) as they are requesting
feedback on improving the experience.

Editor support: All.

## Extract value code action

In this release we introduce a new code action that allows users to
automatically extract method parameter to a separate value.

For example in a invocation such as:

```scala
@main
def main(name: String) =
  println("Hello " + name + "!")
```

when user invokes the new code action they will see:

```scala
@main
def main(name: String) =
  val newValue = "Hello " + name + "!"
  println(newValue)
```

This should work correctly for both Scala 2 and Scala 3, in which case it will
try to maintain your coding style even if a block is needed.

One important limitation is that this new code action doesn't allow extracting
lambdas or partial functions, as this has the potential of breaking your code.

Currently, this works only in method parameters and Metals will try to choose
the exact parameter to extract even if there is a number of nested ones. This
will most likely be improved in the future to also be used in other places such
as if conditions or to allow users to choose the exact parameter they want to
extract.

Editor support: All

## Add command to run current file

Based on the work of [ckipp01](https://github.com/ckipp01) in one of the
[previous releases](https://scalameta.org/metals/blog/2021/04/06/tungsten/#smoother-main-and-test-discovery)
it's now possible to invoke a command to run anything in the current file. This
means that if we use this new command anything that is located within the
currently focused file, whether it's tests or a main class, will be properly
discovered and run.

In Visual Studio Code this replaces the previously default config generation and
now if no `launch.json` is specified in the workspace, any file can be run using
the default run command (`F5`).

Editor support: Any editor that supports DAP

## Support test explorer API.

Metals 0.11.0 implements Visual Studio Code's Testing API. According to the
[documentation](https://code.visualstudio.com/api/extension-guides/testing):

_The Testing API allows Visual Studio Code extensions to discover tests in the
workspace and publish results. Users can execute tests in the Test Explorer view
and from decorations. With these new APIs, Visual Studio Code supports richer
displays of outputs and diffs than was previously possible._

Test Explorer UI is a new default way to run/debug test suites and replaces Code
Lenses. The new UI adds a testing view, which shows all test suites declared in
project's modules. From this panel it's also possible to run/debug test or to
navigate to test's definition.

![test-explorer](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-01-12-aluminium/Z3VtS0O.gif?raw=true)

Code Lenses are still the default in other editors and can be brought back by
setting `"metals.testUserInterface": "Code Lenses"`

Editor support: Visual Studio Code

## Go to definition via links for synthetic decorations.

Since
[Metals v0.9.6](https://scalameta.org/metals/blog/2020/11/20/lithium/#show-implicit-conversions-and-classes)
it's possible to show additional information about sythetic code that is being
generated by the compiler. This includes implicit parameters, classes and
conversions. It wasn't however possible to find their definitions. This
limitation was mostly due to the lack of proper UI options in different editors.

Recently, we've managed to work around it by using command links that can be
rendered in the hover and will bring the user to the right definition.

![synthetic-def](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-01-12-aluminium/YyHVmLX.gif?raw=true)

Editor support: Visual Studio Code, Sublime Text

## Basic Java support

Thanks to the the effort led by [Arthurm1](https://github.com/Arthurm1) Metals
now supports a subset of features for Java files and even Java only build
targets. Currently the biggest missing piece is interactive compiler that would
allow us to properly support features such as completions, hover or signature
help.

![java](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-01-12-aluminium/SJvLTRL.gif?raw=true)

This was possible thanks to using the Java semanticdb plugin included in
[lsif Java project](https://github.com/sourcegraph/lsif-java) from
[sourcegraph](https://about.sourcegraph.com/) and especially the work by
[olafurpg](https://github.com/olafurpg).

The support includes:

- formatting
- code folding
- go to definition
- references
- go to implementation
- rename
- run/debug code lense for Java main classes

These functionalities will also now work thanks to
[dos65](https://github.com/dos65)'s work in dependency sources.

We are still missing some of the interactive features such as:

- hover
- completions
- signature help
- selection range

An important note to make is that, while we still want to support some more
features, Metals is not a Java language server, so we will never offer the same
amount of functionality as the tools and IDEs focused on the Java language.
Current discussion about the scope of Java support can be found in
[this issue](https://github.com/scalameta/metals/issues/3398). Especially, we
will most likely not support any more advanced refactorings or presentation
compiler functions.

**Visual Studio Code:** In case you want to use Metals in a Java only workspace
it's now also possible to start Metals using a new `Start Metals` button in the
the Metals tab. We don't want to start Metals as a default in such cases as some
other language server might be more suitable.

Editor support: All

## Reworked Metals Doctor

Due to the other changes in this release the Metals Doctor needed to be reworked
to better show the different targets that are available in the workspace as well
as try to explain a bit better what is supported and why it might not work.

![doctor](https://user-images.githubusercontent.com/3807253/147948980-401e3f5a-5130-4f19-b59c-d737d9092208.png)

The `type` column can now have 3 different values `Scala <version>`,
`sbt <version>` or `Java`. We also replaced some of the columnsm which are now:

- diagnostics - tells whether and what kind of diagnostics are published by the
  target.
- interactive - tells whether interactive features such as hover or completions
  are working, this should work for all supported Scala and sbt versions.
- semanticdb - explains whther the semanticdb indexes are generated for the
  target
- debugging - tells whether you can run or debug within that target
- Java support - in mixed targets this will tell users if the semanticdb indexes
  are also generated for Java files.

You can always look for explanation of these statuses underneath the table or on
the right in the Recommendation column.

Editor support: All

**Extension authors:**

If you are using the json output method for the doctor, you will need to modify
the way it's displayed according to
[this](https://scalameta.org/metals/docs/integrations/new-editor#run-doctor-1).

## Miscellaneous

- Allow to create trace files for debugging in `.metals` directory.
  [kpodsiad](https://github.com/kpodsiad)
- Fix issues with stale code lens being shown. [dos65](https://github.com/dos65)
- Print Bloop logs when connection errored out.
  [tgodzik](https://github.com/tgodzik)
- Fix issues with given imports in organize imports.
  [tgodzik](https://github.com/tgodzik)
- Fix issues with type on selection in Ammonite scipts.
  [tgodzik](https://github.com/tgodzik)
- [Scala 3] Improve type shown for generics in hover.
  [dos65](https://github.com/dos65)
- [Scala 3] Fix go to defition for givens. [dos65](https://github.com/dos65)
- Use Mill `--import` to import bloop when supported.
  [lolgab](https://github.com/lolgab)
- Enable to create file with just package.
  [kpodsiad](https://github.com/kpodsiad)
- Fix Gradle detection when file other than `build.gradle` is at top level.
  [GavinRay97](https://github.com/GavinRay97)
- Use proper dialect and ignore sbt targets when automatically setting up
  `scalafmt.conf`. [dos65](https://github.com/dos65)
- Detect missing src.zip in doctor.
  [ayoub-benali](https://github.com/ayoub-benali)
- Ensure autoimports don't break scala-cli directives.
  [ckipp01](https://github.com/ckipp01)
- [Scala 3] Don't show error type if it cannot be inferred by the compiler.
  [tgodzik](https://github.com/tgodzik)
- Fix analyze stacktrace when using toplevel methods.
  [tgodzik](https://github.com/tgodzik)
- [Scala 3] Fix insert type in case of type aliases.
  [tgodzik](https://github.com/tgodzik)
- [Scala 3] Use maybeOwner instead of owner when looking for shortened name.
  [ckipp01](https://github.com/ckipp01)
- [Scala 3] Fix autoimport with prefixes. [dos65](https://github.com/dos65)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.10.9..v0.11.0
Tomasz Godzik
Ayoub Benali
Vadim Chelyshov
Chris Kipp
Arthur McGibbon
Kamil Podsiadlo
Gavin Ray
Adrien Bestel
Alexandre Archambault
Lorenzo Gabriele
Thomas Lopatic
```

## Merged PRs

## [v0.11.0](https://github.com/scalameta/metals/tree/v0.11.0) (2022-01-12)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.10.9...v0.11.0)

**Merged pull requests:**

- Update Bloop to 1.4.12 [\#3497](https://github.com/scalameta/metals/pull/3497)
  ([tgodzik](https://github.com/tgodzik))
- Fix issues with renames in Java files
  [\#3495](https://github.com/scalameta/metals/pull/3495)
  ([tgodzik](https://github.com/tgodzik))
- feat(doctor): add in explanations to the json doctor output
  [\#3494](https://github.com/scalameta/metals/pull/3494)
  ([ckipp01](https://github.com/ckipp01))
- Upgrade bloop to 1.4.11-51-ac1d788a
  [\#3492](https://github.com/scalameta/metals/pull/3492)
  ([dos65](https://github.com/dos65))
- Fixed compilation issue
  [\#3496](https://github.com/scalameta/metals/pull/3496)
  ([tgodzik](https://github.com/tgodzik))
- [Java] Support go-to-definition for `*.java` dependency sources
  [\#3470](https://github.com/scalameta/metals/pull/3470)
  ([dos65](https://github.com/dos65))
- Add edit distance for Java files
  [\#3480](https://github.com/scalameta/metals/pull/3480)
  ([tgodzik](https://github.com/tgodzik))
- Show more information in the doctor
  [\#3426](https://github.com/scalameta/metals/pull/3426)
  ([tgodzik](https://github.com/tgodzik))
- Add support for Scala 2.13.8
  [\#3491](https://github.com/scalameta/metals/pull/3491)
  ([tgodzik](https://github.com/tgodzik))
- docs: update user config docs
  [\#3490](https://github.com/scalameta/metals/pull/3490)
  ([ckipp01](https://github.com/ckipp01))
- fix: override pprint in docs
  [\#3489](https://github.com/scalameta/metals/pull/3489)
  ([ckipp01](https://github.com/ckipp01))
- Don't show warnings for .metals/.tmp files
  [\#3488](https://github.com/scalameta/metals/pull/3488)
  ([tgodzik](https://github.com/tgodzik))
- Fix: Docusaurus Edit URL is broken
  [\#3487](https://github.com/scalameta/metals/pull/3487)
  ([abestel](https://github.com/abestel))
- parallelize source file indexing
  [\#3485](https://github.com/scalameta/metals/pull/3485)
  ([Arthurm1](https://github.com/Arthurm1))
- Cache build targets [\#3481](https://github.com/scalameta/metals/pull/3481)
  ([Arthurm1](https://github.com/Arthurm1))
- [Scala3] Fix autoimport with prefixes
  [\#3484](https://github.com/scalameta/metals/pull/3484)
  ([dos65](https://github.com/dos65))
- [Issue Template] Relax requirements
  [\#3474](https://github.com/scalameta/metals/pull/3474)
  ([dos65](https://github.com/dos65))
- [Build] add CommandSuite to TestGroups
  [\#3472](https://github.com/scalameta/metals/pull/3472)
  ([dos65](https://github.com/dos65))
- Add folding range for Java using Scanner
  [\#3468](https://github.com/scalameta/metals/pull/3468)
  ([tgodzik](https://github.com/tgodzik))
- cleanup: remove ./sbt from root of project
  [\#3471](https://github.com/scalameta/metals/pull/3471)
  ([ckipp01](https://github.com/ckipp01))
- docs: migrate gitter references to Discord
  [\#3469](https://github.com/scalameta/metals/pull/3469)
  ([ckipp01](https://github.com/ckipp01))
- refactor: refactor bug_report.md to be a yaml template
  [\#3467](https://github.com/scalameta/metals/pull/3467)
  ([ckipp01](https://github.com/ckipp01))
- Add tests for java go to implementation
  [\#3466](https://github.com/scalameta/metals/pull/3466)
  ([tgodzik](https://github.com/tgodzik))
- fix: use maybeOwner instead of owner when looking for shortened name
  [\#3465](https://github.com/scalameta/metals/pull/3465)
  ([ckipp01](https://github.com/ckipp01))
- [Scala 3] Fix insert type in case of type aliases
  [\#3460](https://github.com/scalameta/metals/pull/3460)
  ([tgodzik](https://github.com/tgodzik))
- fix: reuse main annotation logic from code lens in debug provider
  [\#3463](https://github.com/scalameta/metals/pull/3463)
  ([ckipp01](https://github.com/ckipp01))
- update: update millw and add script for it in the future
  [\#3457](https://github.com/scalameta/metals/pull/3457)
  ([ckipp01](https://github.com/ckipp01))
- Scala Steward - ignore org.eclipse updates
  [\#3453](https://github.com/scalameta/metals/pull/3453)
  ([dos65](https://github.com/dos65))
- Update flyway-core to 8.2.3
  [\#3452](https://github.com/scalameta/metals/pull/3452)
  ([scala-steward](https://github.com/scala-steward))
- Update interface to 1.0.6
  [\#3433](https://github.com/scalameta/metals/pull/3433)
  ([scala-steward](https://github.com/scala-steward))
- Update scribe, scribe-file, scribe-slf4j to 3.6.7
  [\#3432](https://github.com/scalameta/metals/pull/3432)
  ([scala-steward](https://github.com/scala-steward))
- Update mill-contrib-testng to 0.10.0-M5
  [\#3431](https://github.com/scalameta/metals/pull/3431)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt-welcome to 0.2.2
  [\#3429](https://github.com/scalameta/metals/pull/3429)
  ([scala-steward](https://github.com/scala-steward))
- Update jackson-databind to 2.13.1
  [\#3428](https://github.com/scalameta/metals/pull/3428)
  ([scala-steward](https://github.com/scala-steward))
- Update bloop-config, bloop-launcher to 1.4.11-30-75fb3441
  [\#3427](https://github.com/scalameta/metals/pull/3427)
  ([scala-steward](https://github.com/scala-steward))
- Fix analyze stacktrace when using toplevel methods
  [\#3425](https://github.com/scalameta/metals/pull/3425)
  ([tgodzik](https://github.com/tgodzik))
- [Scala 3] Fix issues when type cannot be inferred by the compiler
  [\#3423](https://github.com/scalameta/metals/pull/3423)
  ([tgodzik](https://github.com/tgodzik))
- refactor: take care of some deprecation warnings
  [\#3420](https://github.com/scalameta/metals/pull/3420)
  ([ckipp01](https://github.com/ckipp01))
- refactor: small build cleanups
  [\#3418](https://github.com/scalameta/metals/pull/3418)
  ([ckipp01](https://github.com/ckipp01))
- Fix issue with imports [\#3417](https://github.com/scalameta/metals/pull/3417)
  ([tgodzik](https://github.com/tgodzik))
- [Scalafmt] set proper dialect for sbt-metals
  [\#3416](https://github.com/scalameta/metals/pull/3416)
  ([dos65](https://github.com/dos65))
- docs: add file ids to the docs for new file commands
  [\#3415](https://github.com/scalameta/metals/pull/3415)
  ([ckipp01](https://github.com/ckipp01))
- Add run/debug code lense for Java main classes
  [\#3400](https://github.com/scalameta/metals/pull/3400)
  ([tgodzik](https://github.com/tgodzik))
- fix: ensure autoimports don't break scala-cli directives
  [\#3412](https://github.com/scalameta/metals/pull/3412)
  ([ckipp01](https://github.com/ckipp01))
- dep: update sbt to 1.6.1
  [\#3413](https://github.com/scalameta/metals/pull/3413)
  ([ckipp01](https://github.com/ckipp01))
- [Mtags releases] Fails command if tag name doesn't match any pattern
  [\#3410](https://github.com/scalameta/metals/pull/3410)
  ([dos65](https://github.com/dos65))
- Detect missing src.zip in doctor
  [\#3401](https://github.com/scalameta/metals/pull/3401)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Hardcode eclipse java formatter dependencies
  [\#3406](https://github.com/scalameta/metals/pull/3406)
  ([Arthurm1](https://github.com/Arthurm1))
- Upgrade file-tree-views to 2.1.8. See #3379.
  [\#3405](https://github.com/scalameta/metals/pull/3405)
  ([uncle-betty](https://github.com/uncle-betty))
- [Scalafmt] Fixes for automatic dialect rewrite
  [\#3394](https://github.com/scalameta/metals/pull/3394)
  ([dos65](https://github.com/dos65))
- Handle Java-only build targets
  [\#2520](https://github.com/scalameta/metals/pull/2520)
  ([Arthurm1](https://github.com/Arthurm1))
- Use quick pick instead of message requests for debug discovery
  [\#3392](https://github.com/scalameta/metals/pull/3392)
  ([tgodzik](https://github.com/tgodzik))
- Add support for 3.1.1-RC2
  [\#3391](https://github.com/scalameta/metals/pull/3391)
  ([tgodzik](https://github.com/tgodzik))
- Support sublime command links
  [\#3378](https://github.com/scalameta/metals/pull/3378)
  ([ayoub-benali](https://github.com/ayoub-benali))
- [UNTESTED] Fix Gradle detection, multimodule proj
  [\#3385](https://github.com/scalameta/metals/pull/3385)
  ([GavinRay97](https://github.com/GavinRay97))
- Add Arthur to the team!
  [\#3389](https://github.com/scalameta/metals/pull/3389)
  ([tgodzik](https://github.com/tgodzik))
- [Actions] mtags-auto-release: specify github token
  [\#3390](https://github.com/scalameta/metals/pull/3390)
  ([dos65](https://github.com/dos65))
- Print more info about missing presentation compiler
  [\#3388](https://github.com/scalameta/metals/pull/3388)
  ([kpodsiad](https://github.com/kpodsiad))
- Bump to sbt 1.6.0-RC2 [\#3384](https://github.com/scalameta/metals/pull/3384)
  ([ckipp01](https://github.com/ckipp01))
- Move to Scala 3 published artifacts where possible
  [\#3382](https://github.com/scalameta/metals/pull/3382)
  ([ckipp01](https://github.com/ckipp01))
- Enable to create file with just package
  [\#3375](https://github.com/scalameta/metals/pull/3375)
  ([kpodsiad](https://github.com/kpodsiad))
- Use full range when using go-to definition for synthetics
  [\#3374](https://github.com/scalameta/metals/pull/3374)
  ([tgodzik](https://github.com/tgodzik))
- Fix quickpick [\#3353](https://github.com/scalameta/metals/pull/3353)
  ([kpodsiad](https://github.com/kpodsiad))
- Update requests to 0.7.0
  [\#3367](https://github.com/scalameta/metals/pull/3367)
  ([scala-steward](https://github.com/scala-steward))
- Update scalameta, semanticdb-scalac, ... to 4.4.31
  [\#3372](https://github.com/scalameta/metals/pull/3372)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.2.2
  [\#3370](https://github.com/scalameta/metals/pull/3370)
  ([scala-steward](https://github.com/scala-steward))
- Update undertow-core to 2.2.14.Final
  [\#3369](https://github.com/scalameta/metals/pull/3369)
  ([scala-steward](https://github.com/scala-steward))
- Update ujson to 1.4.3 [\#3368](https://github.com/scalameta/metals/pull/3368)
  ([scala-steward](https://github.com/scala-steward))
- Update pprint to 0.7.1 [\#3366](https://github.com/scalameta/metals/pull/3366)
  ([scala-steward](https://github.com/scala-steward))
- Update geny to 0.7.0 [\#3365](https://github.com/scalameta/metals/pull/3365)
  ([scala-steward](https://github.com/scala-steward))
- Update bloop-config, bloop-launcher to 1.4.11-19-93ebe2c6
  [\#3363](https://github.com/scalameta/metals/pull/3363)
  ([scala-steward](https://github.com/scala-steward))
- [Scala3] Fix ImportMissingSymbol code action
  [\#3362](https://github.com/scalameta/metals/pull/3362)
  ([dos65](https://github.com/dos65))
- Send Code lens refresh when supported by client
  [\#3355](https://github.com/scalameta/metals/pull/3355)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Add go to definition via links to synthetic decorations
  [\#3360](https://github.com/scalameta/metals/pull/3360)
  ([tgodzik](https://github.com/tgodzik))
- [Build] Fix quick-publish-local
  [\#3361](https://github.com/scalameta/metals/pull/3361)
  ([dos65](https://github.com/dos65))
- Add information about source of exceptions
  [\#3340](https://github.com/scalameta/metals/pull/3340)
  ([tgodzik](https://github.com/tgodzik))
- Remove stray pprint [\#3357](https://github.com/scalameta/metals/pull/3357)
  ([ckipp01](https://github.com/ckipp01))
- Use Mill `--import` to import bloop when supported
  [\#3356](https://github.com/scalameta/metals/pull/3356)
  ([lolgab](https://github.com/lolgab))
- Add test discovery endpoint
  [\#3277](https://github.com/scalameta/metals/pull/3277)
  ([kpodsiad](https://github.com/kpodsiad))
- [Build] Add `quick-publish-local` command
  [\#3351](https://github.com/scalameta/metals/pull/3351)
  ([dos65](https://github.com/dos65))
- Actions - set jdk11 for mtags auto release
  [\#3348](https://github.com/scalameta/metals/pull/3348)
  ([dos65](https://github.com/dos65))
- Wrap quickpick and input box results into options
  [\#3344](https://github.com/scalameta/metals/pull/3344)
  ([kpodsiad](https://github.com/kpodsiad))
- Cross tests - fix ignorance for `test-mtags-dyn`
  [\#3339](https://github.com/scalameta/metals/pull/3339)
  ([dos65](https://github.com/dos65))
- [Actions] Fixes for mtags-auto-release workflow
  [\#3337](https://github.com/scalameta/metals/pull/3337)
  ([dos65](https://github.com/dos65))
- Remove sublime version from the doc
  [\#3334](https://github.com/scalameta/metals/pull/3334)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Fix issues with non-latest Scala versions
  [\#3332](https://github.com/scalameta/metals/pull/3332)
  ([tgodzik](https://github.com/tgodzik))
- Release process: publish `mtags` for the latest Metals release
  [\#3281](https://github.com/scalameta/metals/pull/3281)
  ([dos65](https://github.com/dos65))
- Improve the "(currently using)" message during bsp-switch.
  [\#3330](https://github.com/scalameta/metals/pull/3330)
  ([ckipp01](https://github.com/ckipp01))
- Adjust json trace docs [\#3279](https://github.com/scalameta/metals/pull/3279)
  ([kpodsiad](https://github.com/kpodsiad))
- [Scala3] Fix go to defition for givens
  [\#3309](https://github.com/scalameta/metals/pull/3309)
  ([dos65](https://github.com/dos65))
- Update xnio-nio to 3.8.5.Final
  [\#3327](https://github.com/scalameta/metals/pull/3327)
  ([scala-steward](https://github.com/scala-steward))
- Bump coursier/setup-action from 1.1.1 to 1.1.2
  [\#3329](https://github.com/scalameta/metals/pull/3329)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- Update scalafix-interfaces to 0.9.33
  [\#3328](https://github.com/scalameta/metals/pull/3328)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.0.5
  [\#3326](https://github.com/scalameta/metals/pull/3326)
  ([scala-steward](https://github.com/scala-steward))
- Update undertow-core to 2.2.13.Final
  [\#3325](https://github.com/scalameta/metals/pull/3325)
  ([scala-steward](https://github.com/scala-steward))
- Update geny to 0.6.11 [\#3324](https://github.com/scalameta/metals/pull/3324)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt-scalafix, scalafix-interfaces to 0.9.33
  [\#3322](https://github.com/scalameta/metals/pull/3322)
  ([scala-steward](https://github.com/scala-steward))
- [Scala3] Hover - improve tpe selection for signature and expression
  [\#3320](https://github.com/scalameta/metals/pull/3320)
  ([dos65](https://github.com/dos65))
- Move inactive maintainers to a separate list
  [\#3319](https://github.com/scalameta/metals/pull/3319)
  ([tgodzik](https://github.com/tgodzik))
- Remove unused stuff [\#3321](https://github.com/scalameta/metals/pull/3321)
  ([alexarchambault](https://github.com/alexarchambault))
- Add option to run everything in the current file
  [\#3311](https://github.com/scalameta/metals/pull/3311)
  ([tgodzik](https://github.com/tgodzik))
- Add extract value code action
  [\#3297](https://github.com/scalameta/metals/pull/3297)
  ([tgodzik](https://github.com/tgodzik))
- Welcome Kamil to the team!
  [\#3317](https://github.com/scalameta/metals/pull/3317)
  ([tgodzik](https://github.com/tgodzik))
- Document support of find text in dependency command in sublime
  [\#3318](https://github.com/scalameta/metals/pull/3318)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Better support for mill BSP
  [\#3308](https://github.com/scalameta/metals/pull/3308)
  ([ckipp01](https://github.com/ckipp01))
- Fix json rendering in documentation
  [\#3316](https://github.com/scalameta/metals/pull/3316)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Add missing commands to client commands documentation
  [\#3315](https://github.com/scalameta/metals/pull/3315)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Add isCommandInHtmlSupported to InitializationOptions doc
  [\#3314](https://github.com/scalameta/metals/pull/3314)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Update sublime doc regarding Hover for selection
  [\#3313](https://github.com/scalameta/metals/pull/3313)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Simplify usage of sbt server when no .bsp/sbt.json exists
  [\#3304](https://github.com/scalameta/metals/pull/3304)
  ([ckipp01](https://github.com/ckipp01))
- Support Scala3-NIGHTLY releases
  [\#3280](https://github.com/scalameta/metals/pull/3280)
  ([dos65](https://github.com/dos65))
- Document source file analyzer support in sublime
  [\#3310](https://github.com/scalameta/metals/pull/3310)
  ([ayoub-benali](https://github.com/ayoub-benali))
- ScalaVersion - discover new mtags version by coursier
  [\#3278](https://github.com/scalameta/metals/pull/3278)
  ([dos65](https://github.com/dos65))
- Update ammonite to 2.4.1
  [\#3292](https://github.com/scalameta/metals/pull/3292)
  ([tgodzik](https://github.com/tgodzik))
- Fix expression for selection range in Ammonite
  [\#3303](https://github.com/scalameta/metals/pull/3303)
  ([tgodzik](https://github.com/tgodzik))
- [Scala 3] Add selection range
  [\#3276](https://github.com/scalameta/metals/pull/3276)
  ([tgodzik](https://github.com/tgodzik))
- Set rangeHoverProvider under experimental capabilities
  [\#3293](https://github.com/scalameta/metals/pull/3293)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Update to latest millw 0.3.9
  [\#3295](https://github.com/scalameta/metals/pull/3295)
  ([ckipp01](https://github.com/ckipp01))
- Update scalafix-interfaces to 0.9.32
  [\#3291](https://github.com/scalameta/metals/pull/3291)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt-scalafix, scalafix-interfaces to 0.9.32
  [\#3287](https://github.com/scalameta/metals/pull/3287)
  ([scala-steward](https://github.com/scala-steward))
- Update mill-contrib-testng to 0.9.10
  [\#3288](https://github.com/scalameta/metals/pull/3288)
  ([scala-steward](https://github.com/scala-steward))
- Update qdox to 2.0.1 [\#3289](https://github.com/scalameta/metals/pull/3289)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.0.4
  [\#3290](https://github.com/scalameta/metals/pull/3290)
  ([scala-steward](https://github.com/scala-steward))
- docs: update commands that now use `TextDocumentPositionParams`
  [\#3284](https://github.com/scalameta/metals/pull/3284)
  ([ckipp01](https://github.com/ckipp01))
- Update organize-imports rule with a fix for givens
  [\#3273](https://github.com/scalameta/metals/pull/3273)
  ([tgodzik](https://github.com/tgodzik))
- Add example about attaching debugger
  [\#3275](https://github.com/scalameta/metals/pull/3275)
  ([tgodzik](https://github.com/tgodzik))
- Add CFR class file viewer
  [\#3247](https://github.com/scalameta/metals/pull/3247)
  ([Arthurm1](https://github.com/Arthurm1))
- Print Bloop logs when connection errored out
  [\#3274](https://github.com/scalameta/metals/pull/3274)
  ([tgodzik](https://github.com/tgodzik))
- Formatter: always specify global `runner.dialect`.
  [\#3259](https://github.com/scalameta/metals/pull/3259)
  ([dos65](https://github.com/dos65))
- Finer grained rules on build errors for debug discovery.
  [\#3271](https://github.com/scalameta/metals/pull/3271)
  ([ckipp01](https://github.com/ckipp01))
- RunDebugLens - fix stale lens
  [\#3270](https://github.com/scalameta/metals/pull/3270)
  ([dos65](https://github.com/dos65))
- [Docs] Align the info about endpoints and their parameters with latest release
  [\#3232](https://github.com/scalameta/metals/pull/3232)
  ([dos65](https://github.com/dos65))
- SuperMethodCodeLens - do not show stale lenses
  [\#3267](https://github.com/scalameta/metals/pull/3267)
  ([dos65](https://github.com/dos65))
- Take into account trace files in .metals directory
  [\#3103](https://github.com/scalameta/metals/pull/3103)
  ([kpodsiad](https://github.com/kpodsiad))
- Fix issue with empty string in completions
  [\#3269](https://github.com/scalameta/metals/pull/3269)
  ([tgodzik](https://github.com/tgodzik))
- Update versions [\#3268](https://github.com/scalameta/metals/pull/3268)
  ([tgodzik](https://github.com/tgodzik))
- Add release notes for Metals v0.10.9
  [\#3264](https://github.com/scalameta/metals/pull/3264)
  ([tgodzik](https://github.com/tgodzik))
