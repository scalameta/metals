---
authors: kpodsiad
title: Metals v0.11.2 - Aluminium
---

We're happy to announce the release of Metals v0.11.2, which focuses on
improving overall user experience.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">197</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">94</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">11</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">19</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">7</td>
  </tr>
</tbody>

</table>

For full details: https://github.com/scalameta/metals/milestone/?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Reduce indexing time in monorepos on MacOS
- Display build target info
- View source jar files as virtual docs
- Improve completions for Scala 3
- [Test Explorer] Detect and run single tests for JUnit4

## Reduce indexing time in monorepos on MacOS

Thanks to changes made by [Duhemm](https://github.com/Duhemm), Metals on MacOS
can now index monorepo workspaces much faster than before. After changes, we
observed at times **over 10 times faster** indexing times in the workspaces
where only a few submodules needed to be imported.

Here are a few examples of indexing improvement. They are not actual benchmarks
of any kind, but more of rough estimations of improvements in a couple of
example repos.

| Before      | After      |
| ----------- | ---------- |
| \>10minutes | ~30seconds |
| ~40seconds  | ~4s        |

However, take into mind that indexing speedup heavily depends on your repository
structure. The more imported modules which don't need to be imported, the
greater the speed improvement is.

If you are interested in details you can check out the related
[pull request](https://github.com/scalameta/metals/pull/3665).

## Display build target info

Thanks to the [Arthurm1](https://github.com/Arthurm1) Metals is now able to
display all important information about modules. This brand new feature gathers
in one view information such as:

- javac and scalac options
- dependent modules
- projects classpath
- and many more

![display-build-target-info](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-03-08-aluminium/XGyJEsl.gif?raw=true)

## [vscode] View source jar files as virtual docs

Previously, when the client wanted to browse files in source jars source, these
files were extracted and saved in the `metals/readonly/dependencies` directory.
With the help of
[virtual documents](https://code.visualstudio.com/api/extension-guides/virtual-documents)
Metals can show you dependencies in readonly files without unnecessary copying.
Thanks [Arthurm1](https://github.com/Arthurm1) for this feature!

Together with Metals tab, this feature could be used to browse through your
dependencies' sources. Just run the `Metals: Reveal Active File in Side Bar`
command and browse through both dependencies and source code seamlessly.

![virtual-docs-navigation](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-03-08-aluminium/HsuW8Hn.gif?raw=true)

Currently, `Metals: Reveal Active File in Side Bar` works only for Scala 2.

## Improve completions for Scala 3

Completion suggestions for different Scala keywords now work with most of the
Scala 3 keywords. This includes for example `given` and `enum`, it should also
work even if defining things in toplevel without a wrapping class or object.
![keyword-completions](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-03-08-aluminium/4BUxCDK.gif?raw=true)

Another improvement for Scala 3 completions is better support for showing scope
completions, when writing in an empty line. Previously, we would not show
correct completions when no identifier was specified.

```scala
object Foo:
  def bar: Int = 42
  def baz: Int =
    val x = 1
    val y = 2
  @@
```

In the above situation with cursor position indicated by `@@` we will now
properly show `bar` and `baz` completions.

## [Test Explorer] Detect and run single tests for JUnit4

We're actively working on improving the Test Explorer and making it better with
each release. From this release, Metals is able to run or debug single test in
JUnit4.

Currently, this feature **only works when using Bloop as your build server**,
but in a future release there will be support added for sbt as well.

![test-explorer-single-tests](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-03-08-aluminium/FbgSTGr.gif?raw=true)

## [vscode] Add mirror setting to help coursier set up

In order to bootstrap Metals, vscode's extension uses the coursier script which
needs access to `repo1.maven.org`. Previously, this URL couldn't be configured
which was causing problems on the machines that didn't have access to
repo1.maven.org.

Thanks to [tgodzik](https://github.com/tgodzik)'s work from now on it's possible
to define `metals.coursierMirror` property.

![coursier-mirror](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-03-08-aluminium/iLB079M.png?raw=true)

More information about mirrors can be found at
[coursier documentation](https://get-coursier.io/blog/#mirrors).

## Miscellaneous

- Show implicit decorations in worksheets
  [\#3582](https://github.com/scalameta/metals/pull/3582)
  ([tgodzik](https://github.com/tgodzik))
- Add current env variables to bloopInstall
  [\#3662](https://github.com/scalameta/metals/pull/3662)
  ([tgodzik](https://github.com/tgodzik))
- Fix issues with Ammonite multistage scripts
  [\#3627](https://github.com/scalameta/metals/pull/3627)
  ([tgodzik](https://github.com/tgodzik))
- Print information about candidates searched when missing JDK sources
  [\#3606](https://github.com/scalameta/metals/pull/3606)
  ([tgodzik](https://github.com/tgodzik))

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.1..v0.11.2
    37	Tomasz Godzik
    24	Vadim Chelyshov
    22	Scala Steward
    15	Arthur McGibbon
     8	Kamil Podsiadlo
     7	ckipp01
     6	Kamil Podsiadło
     1	Martin Duhem
     1	Adrien Piquerez
     1	Jerome Wuerf
     1	Hugo van Rijswijk
```

## Merged PRs

## [v0.11.2](https://github.com/scalameta/metals/tree/v0.11.2) (2022-03-08)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.1...v0.11.2)

**Merged pull requests:**

- Go to definition of synthetic symbols.
  [\#3683](https://github.com/scalameta/metals/pull/3683)
  ([olafurpg](https://github.com/olafurpg))
- Don't watch entire workspace on MacOS
  [\#3665](https://github.com/scalameta/metals/pull/3665)
  ([Duhemm](https://github.com/Duhemm))
- fix: Add test case for topelevel tuple hover
  [\#3676](https://github.com/scalameta/metals/pull/3676)
  ([tgodzik](https://github.com/tgodzik))
- Scala3 PC refactorings [\#3651](https://github.com/scalameta/metals/pull/3651)
  ([dos65](https://github.com/dos65))
- fix: Add tests for Scala 3 toplevel enums
  [\#3674](https://github.com/scalameta/metals/pull/3674)
  ([tgodzik](https://github.com/tgodzik))
- chore: fix flaky test provider suite
  [\#3673](https://github.com/scalameta/metals/pull/3673)
  ([kpodsiad](https://github.com/kpodsiad))
- Update scribe, scribe-file, scribe-slf4j to 3.8.0
  [\#3669](https://github.com/scalameta/metals/pull/3669)
  ([scala-steward](https://github.com/scala-steward))
- Update mdoc-interfaces to 2.3.1
  [\#3672](https://github.com/scalameta/metals/pull/3672)
  ([scala-steward](https://github.com/scala-steward))
- Update mdoc, mdoc-interfaces, sbt-mdoc to 2.3.1
  [\#3671](https://github.com/scalameta/metals/pull/3671)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt-buildinfo to 0.11.0
  [\#3667](https://github.com/scalameta/metals/pull/3667)
  ([scala-steward](https://github.com/scala-steward))
- Update guava to 31.1-jre
  [\#3668](https://github.com/scalameta/metals/pull/3668)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.5.1
  [\#3670](https://github.com/scalameta/metals/pull/3670)
  ([scala-steward](https://github.com/scala-steward))
- fix: Add test case when using Left class and Scala 3
  [\#3666](https://github.com/scalameta/metals/pull/3666)
  ([tgodzik](https://github.com/tgodzik))
- Change hashing to lastModified
  [\#3611](https://github.com/scalameta/metals/pull/3611)
  ([tgodzik](https://github.com/tgodzik))
- Update Bloop to 1.4.13 [\#3664](https://github.com/scalameta/metals/pull/3664)
  ([tgodzik](https://github.com/tgodzik))
- Fix issues with nightlies tests
  [\#3663](https://github.com/scalameta/metals/pull/3663)
  ([tgodzik](https://github.com/tgodzik))
- fix: do not show test explorer related error for client which don't implement
  it [\#3661](https://github.com/scalameta/metals/pull/3661)
  ([kpodsiad](https://github.com/kpodsiad))
- Add current env variables to bloopInstall
  [\#3662](https://github.com/scalameta/metals/pull/3662)
  ([tgodzik](https://github.com/tgodzik))
- docs: add a post about release-related changes
  [\#3653](https://github.com/scalameta/metals/pull/3653)
  ([dos65](https://github.com/dos65))
- fix: support Scala 2.11 PC on jdk higher than 8
  [\#3658](https://github.com/scalameta/metals/pull/3658)
  ([dos65](https://github.com/dos65))
- feat: allow running single test
  [\#3619](https://github.com/scalameta/metals/pull/3619)
  ([kpodsiad](https://github.com/kpodsiad))
- Bump scalameta to 4.5.0
  [\#3652](https://github.com/scalameta/metals/pull/3652)
  ([tgodzik](https://github.com/tgodzik))
- Change displayBuildTarget to listBuildTargets
  [\#3649](https://github.com/scalameta/metals/pull/3649)
  ([Arthurm1](https://github.com/Arthurm1))
- Fix issues in tests for Scala 3 Nightlies
  [\#3650](https://github.com/scalameta/metals/pull/3650)
  ([tgodzik](https://github.com/tgodzik))
- fix: release workflow - prevent parallel publishing to sonatype
  [\#3644](https://github.com/scalameta/metals/pull/3644)
  ([dos65](https://github.com/dos65))
- Cancel PRs automatically if new commits are pushed
  [\#3646](https://github.com/scalameta/metals/pull/3646)
  ([tgodzik](https://github.com/tgodzik))
- fix: check scala3 nigtly - exclude existing latest
  [\#3645](https://github.com/scalameta/metals/pull/3645)
  ([dos65](https://github.com/dos65))
- Scala3 emptyline completions
  [\#3629](https://github.com/scalameta/metals/pull/3629)
  ([dos65](https://github.com/dos65))
- Update millw to 0.4.2 [\#3641](https://github.com/scalameta/metals/pull/3641)
  ([tgodzik](https://github.com/tgodzik))
- fix: actions, discover new scala3 nigtly properly
  [\#3640](https://github.com/scalameta/metals/pull/3640)
  ([dos65](https://github.com/dos65))
- Fix FileDecoderProviderLspSuite to work locally
  [\#3639](https://github.com/scalameta/metals/pull/3639)
  ([tgodzik](https://github.com/tgodzik))
- Update scribe, scribe-file, scribe-slf4j to 3.7.1
  [\#3634](https://github.com/scalameta/metals/pull/3634)
  ([scala-steward](https://github.com/scala-steward))
- Fix showing virtual docs in jars with paths that need escaping
  [\#3632](https://github.com/scalameta/metals/pull/3632)
  ([tgodzik](https://github.com/tgodzik))
- Update undertow-core to 2.2.16.Final
  [\#3635](https://github.com/scalameta/metals/pull/3635)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.4.4
  [\#3636](https://github.com/scalameta/metals/pull/3636)
  ([scala-steward](https://github.com/scala-steward))
- Update ammonite-util to 2.5.2
  [\#3633](https://github.com/scalameta/metals/pull/3633)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt, scripted-plugin to 1.6.2
  [\#3638](https://github.com/scalameta/metals/pull/3638)
  ([scala-steward](https://github.com/scala-steward))
- Update xnio-nio to 3.8.6.Final
  [\#3637](https://github.com/scalameta/metals/pull/3637)
  ([scala-steward](https://github.com/scala-steward))
- Index non-scala target dependency sources
  [\#3628](https://github.com/scalameta/metals/pull/3628)
  ([Arthurm1](https://github.com/Arthurm1))
- Fix issues with Ammonite multistage scripts
  [\#3627](https://github.com/scalameta/metals/pull/3627)
  ([tgodzik](https://github.com/tgodzik))
- View source jar files as virtual docs
  [\#3143](https://github.com/scalameta/metals/pull/3143)
  ([Arthurm1](https://github.com/Arthurm1))
- Bump debug-adapter to 2.0.13
  [\#3626](https://github.com/scalameta/metals/pull/3626)
  ([adpi2](https://github.com/adpi2))
- docs: provide latest versions
  [\#3618](https://github.com/scalameta/metals/pull/3618)
  ([dos65](https://github.com/dos65))
- docs: describe how to add new Scala version support to the existing release
  [\#3617](https://github.com/scalameta/metals/pull/3617)
  ([dos65](https://github.com/dos65))
- feat, refactor: use notification to send test updates
  [\#3554](https://github.com/scalameta/metals/pull/3554)
  ([kpodsiad](https://github.com/kpodsiad))
- Display build target info
  [\#3380](https://github.com/scalameta/metals/pull/3380)
  ([Arthurm1](https://github.com/Arthurm1))
- scalafmt - newlines.inInterpolation = avoid
  [\#3614](https://github.com/scalameta/metals/pull/3614)
  ([dos65](https://github.com/dos65))
- refactor: [Scala 3] Extract hover to a separate file
  [\#3613](https://github.com/scalameta/metals/pull/3613)
  ([tgodzik](https://github.com/tgodzik))
- chore(fmt): bump scalafmt to 3.4.0
  [\#3610](https://github.com/scalameta/metals/pull/3610)
  ([ckipp01](https://github.com/ckipp01))
- fix: compare long numbers in TVP
  [\#3607](https://github.com/scalameta/metals/pull/3607)
  ([kpodsiad](https://github.com/kpodsiad))
- Update scribe to 3.6.10
  [\#3598](https://github.com/scalameta/metals/pull/3598)
  ([tgodzik](https://github.com/tgodzik))
- Ignore UncheckedIOException coming from broken source jars
  [\#3609](https://github.com/scalameta/metals/pull/3609)
  ([tgodzik](https://github.com/tgodzik))
- Print information about candidates searched when missing JDK sources
  [\#3606](https://github.com/scalameta/metals/pull/3606)
  ([tgodzik](https://github.com/tgodzik))
- feat: add range to symbol definition
  [\#3605](https://github.com/scalameta/metals/pull/3605)
  ([kpodsiad](https://github.com/kpodsiad))
- Revert to Mdoc 2.2.24 for Scala 2.11
  [\#3601](https://github.com/scalameta/metals/pull/3601)
  ([tgodzik](https://github.com/tgodzik))
- chore(ci): update setup-action
  [\#3603](https://github.com/scalameta/metals/pull/3603)
  ([ckipp01](https://github.com/ckipp01))
- Migrate to the newest H2 version
  [\#3595](https://github.com/scalameta/metals/pull/3595)
  ([tgodzik](https://github.com/tgodzik))
- refactor: `SemanticdbFeatureProvider` trait
  [\#3602](https://github.com/scalameta/metals/pull/3602)
  ([kpodsiad](https://github.com/kpodsiad))
- Do not insert inferred type on variable bound to another
  [\#3600](https://github.com/scalameta/metals/pull/3600)
  ([tgodzik](https://github.com/tgodzik))
- Show implicit decorations in worksheets
  [\#3582](https://github.com/scalameta/metals/pull/3582)
  ([tgodzik](https://github.com/tgodzik))
- Update scalameta, semanticdb-scalac, ... to 4.4.33
  [\#3592](https://github.com/scalameta/metals/pull/3592)
  ([scala-steward](https://github.com/scala-steward))
- Update mdoc, mdoc-interfaces, sbt-mdoc to 2.3.0
  [\#3591](https://github.com/scalameta/metals/pull/3591)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.4.3
  [\#3590](https://github.com/scalameta/metals/pull/3590)
  ([scala-steward](https://github.com/scala-steward))
- Update ujson to 1.5.0 [\#3588](https://github.com/scalameta/metals/pull/3588)
  ([scala-steward](https://github.com/scala-steward))
- Update geny to 0.7.1 [\#3587](https://github.com/scalameta/metals/pull/3587)
  ([scala-steward](https://github.com/scala-steward))
- Update metaconfig-core to 0.10.0
  [\#3585](https://github.com/scalameta/metals/pull/3585)
  ([scala-steward](https://github.com/scala-steward))
- Fix goto definition for local synthetics
  [\#3580](https://github.com/scalameta/metals/pull/3580)
  ([tgodzik](https://github.com/tgodzik))
- minor: remove non-relevant things
  [\#3564](https://github.com/scalameta/metals/pull/3564)
  ([dos65](https://github.com/dos65))
- Make sure we only analyze stacktrace lines that do look the part
  [\#3578](https://github.com/scalameta/metals/pull/3578)
  ([tgodzik](https://github.com/tgodzik))
- docs(ammonite): add note about ammonite support
  [\#3579](https://github.com/scalameta/metals/pull/3579)
  ([ckipp01](https://github.com/ckipp01))
- Don't focus diagnotics if compialtion returned with error
  [\#3577](https://github.com/scalameta/metals/pull/3577)
  ([tgodzik](https://github.com/tgodzik))
- Pass -Xsource:3 to scalafix organize-imports action if present
  [\#3574](https://github.com/scalameta/metals/pull/3574)
  ([hugo-vrijswijk](https://github.com/hugo-vrijswijk))
- Insert missing comma [\#3573](https://github.com/scalameta/metals/pull/3573)
  ([ossScharom](https://github.com/ossScharom))
- cleanup: remove unused stuff from CompilerInterfaces
  [\#3572](https://github.com/scalameta/metals/pull/3572)
  ([ckipp01](https://github.com/ckipp01))
- fix: add possibility to define callback for `BatchedFunction`
  [\#3571](https://github.com/scalameta/metals/pull/3571)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: Open other window when using Analyze Stacktrace
  [\#3540](https://github.com/scalameta/metals/pull/3540)
  ([tgodzik](https://github.com/tgodzik))
- feature: Add support for Scala 3.1.2-RC1
  [\#3569](https://github.com/scalameta/metals/pull/3569)
  ([tgodzik](https://github.com/tgodzik))
- fix: [Scala 3] Show exact literal type
  [\#3567](https://github.com/scalameta/metals/pull/3567)
  ([tgodzik](https://github.com/tgodzik))
- feature: [Scala 3] Add keyword completions
  [\#3560](https://github.com/scalameta/metals/pull/3560)
  ([tgodzik](https://github.com/tgodzik))
- release: fix last 5 nigtly version selection
  [\#3558](https://github.com/scalameta/metals/pull/3558)
  ([dos65](https://github.com/dos65))
- Add support for Scala 3.1.1
  [\#3555](https://github.com/scalameta/metals/pull/3555)
  ([tgodzik](https://github.com/tgodzik))
- actions: mtags release adjustments
  [\#3557](https://github.com/scalameta/metals/pull/3557)
  ([dos65](https://github.com/dos65))
- [Actions] Mtags publishing - move all logic to ci-script
  [\#3553](https://github.com/scalameta/metals/pull/3553)
  ([dos65](https://github.com/dos65))
- docs: mention about pre-release version of Metals extension
  [\#3552](https://github.com/scalameta/metals/pull/3552)
  ([kpodsiad](https://github.com/kpodsiad))
- [Actions] Mtags release
  [\#3551](https://github.com/scalameta/metals/pull/3551)
  ([dos65](https://github.com/dos65))
- docs: Add docs for discover-test-suites
  [\#3546](https://github.com/scalameta/metals/pull/3546)
  ([ckipp01](https://github.com/ckipp01))
- docs: add a small note about decode not working on extra file types.
  [\#3549](https://github.com/scalameta/metals/pull/3549)
  ([ckipp01](https://github.com/ckipp01))
- [Actions] Scala3 Nightly check - yet another fix
  [\#3548](https://github.com/scalameta/metals/pull/3548)
  ([dos65](https://github.com/dos65))
- [Actions] One more fix to check_scala3_nightly job
  [\#3547](https://github.com/scalameta/metals/pull/3547)
  ([dos65](https://github.com/dos65))
- Compare only uppercase MD5
  [\#3545](https://github.com/scalameta/metals/pull/3545)
  ([tgodzik](https://github.com/tgodzik))
- [JavaInteractiveSemanticdb] Add test-case to cover --patch-module flag usage
  [\#3538](https://github.com/scalameta/metals/pull/3538)
  ([dos65](https://github.com/dos65))
- [Actions] Install coursier for nightly checks
  [\#3543](https://github.com/scalameta/metals/pull/3543)
  ([dos65](https://github.com/dos65))
- refactor: check lowercase test-user-interface
  [\#3544](https://github.com/scalameta/metals/pull/3544)
  ([ckipp01](https://github.com/ckipp01))
- [Actions] Trigger Scala3-nightly mtags publishing automatically
  [\#3541](https://github.com/scalameta/metals/pull/3541)
  ([dos65](https://github.com/dos65))
- Add release notes for 0.11.1
  [\#3524](https://github.com/scalameta/metals/pull/3524)
  ([tgodzik](https://github.com/tgodzik))
