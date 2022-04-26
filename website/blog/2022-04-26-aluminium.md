---
author: Kamil Podsiadlo
title: Metals v0.11.3 - Aluminium
authorURL: https://twitter.com/podsiadel
authorImageURL: https://github.com/kpodsiad.png
---

We're happy to announce the release of Metals v0.11.3.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">231</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">110</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">17</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">21</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">7</td>
  </tr>
</tbody>
</table>

This release uses a new version of [Bloop](https://github.com/scalacenter/bloop/blob/main/notes/v1.5.0.md).
This should fix a few bugs regarding e.g. stale diagnostics in Scala 3. Moreover, the new release brings also
a few UX improvements regarding Doctor view and running/debugging your code.

For full details: https://github.com/scalameta/metals/milestone/48?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from [Lunatech](https://lunatech.com) along with contributors from
the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Add more information to the Doctor view
- Create companion object code action
- Add status bar when starting a debug session
- Ensure the "no run or test" message is shown to user.
- Show better unnaply signatures
- Provide an easier way to configure bloop settings
- Better MUnit support in Test Explorer
- Better sbt BSP integration
- Improved Scala3 support

## Add more information to the Doctor view

Thanks to the joined effort of [\#3772](https://github.com/scalameta/metals/pull/3772), [\#3763](https://github.com/scalameta/metals/pull/3763) and [\#3710](https://github.com/scalameta/metals/pull/3710) the Doctor view currently:

- shows the Metals server version and information about Java used to run the Metals server
- displays the compilation status of project
- allows to navigate to the build target info for each target

![new-doctor-view](https://imgur.com/ByzzlM8.png)

## Create companion object code action

Thanks to the efforts of [zmerr](https://github.com/zmerr), Metals offers a new code action - `Create companion object`.
As its name suggests, it can be used to automatically create a companion object for the given class, trait or enum.

![create-companion-object-code-action](https://imgur.com/HW5rr5f.gif)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

## Add status bar when starting a debug session

Starting a debug session is a complex task, which requires a few steps:

- Metals has to compile your project and create a debug session
- debugger has to be initialized (configuration, breakpoints, etc.)

Very often each of these steps takes up to a few seconds in order to complete.
Until now, the user had no idea what is happening because Metals didn't show any progress indicator.
[ckipp01](https://github.com/ckipp01) addressed this problem and added a status bar for each of the steps.

![start-debug-session-status-bar](https://imgur.com/qh7Jzdy.gif)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

## Ensure the "no run or test" message is shown to user.

There is a command `Run main class or tests in current file` which is a convenient way, as name suggests, of running main class:
![run-main](https://imgur.com/rShZ8L3.gif)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

or running tests in the current file:
![run-tests](https://imgur.com/BtbOubC.gif)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

It is also possible to just press `F5` in file and Metals will execute main class or run tests in the current file if no run configuration is defined.

However, this command was silently failing when there was no class or test to run in the file.
This is no longer the case, now Metals will display proper error message in this scenario.
![no-class-to-run](https://imgur.com/xda3ApF.gif)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

## Show better unnaply signatures

Previously, when pattern matching Metals was showing real `unnaply` method signature, which wasn't very useful.
Now, Metals properly uses unapply result type to show what types can be matched on.
![unnaply-signatures](https://imgur.com/Gzg11YT.png)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

## Provide an easier way to configure bloop settings

Bloop [JVM options](https://scalacenter.github.io/bloop/docs/server-reference#custom-java-options) can be
configured through a global config file. Thanks to the [zmerr](https://github.com/zmerr), Metals is capable of changing those settings.
Each modification of `Bloop Jvm Properties` settings will try to update that global file if possible.
In case of manually configured settings, Metals will inform user about it and ask for their action.

![update-bloop-settings](https://imgur.com/Xz2gO0h.gif)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

## Better MUnit support in Test Explorer

Disclaimer: **this works only when Bloop is a build server**

Test Explorer in Metals is now able to detect some single test cases for [MUnit](https://scalameta.org/munit/) test framework.
![munit-single-tests](https://imgur.com/QQMLy6M.png)
Theme: [One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

More information about current MUnit support status can be found at this [issue](https://github.com/scalameta/metals/issues/3771).

## Better sbt BSP integration

From this release Metals will no longer run the compilation when sbt generates files under the `src-managed` directory.
The previous behaviour was related to some of the source generating sbt plugins. When used with sbt BSP users would experience  continuous compilation.
For more details, see [\#2183](https://github.com/scalameta/metals/issues/2183).

## Improved Scala3 support

Metals 0.11.3 now includes some better Scala3 supports in go-to-definition, rename symbols, and displaying hover.

For more information, check out the following pull requests:

- [Don't show a tooltip on hover if cursor is not on a symbol by tanishiking · Pull Request #3792 · scalameta/metals](https://github.com/scalameta/metals/pull/3792)
- [fix: (Scala3) Don't navigate to enclosing symbols on go-to-definition if cursor is not on symbol by tanishiking · Pull Request #3807 · scalameta/metals](https://github.com/scalameta/metals/pull/3807)
- [fix: Rename extension parameter in Scala3 by tanishiking · Pull Request #3800 · scalameta/metals](https://github.com/scalameta/metals/pull/3800)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.2..v0.11.3
    32	Rikito Taniguchi
    30	Scala Steward
    19	Tomasz Godzik
    17	Vadim Chelyshov
    15	Kamil Podsiadło
    15	zmerr
    12	ckipp01
    11	Alexandre Archambault
     5	Kamil Podsiadlo
     4	Martin Duhem
     3	Arthur McGibbon
     3	tgodzik
     2	Georg Pfuetzenreuter
     1	Gabriele Petronella
     1	Chris Kipp
     1	dependabot[bot]
     1	Jens Petersen
```

## Merged PRs

## [v0.11.3](https://github.com/scalameta/metals/tree/v0.11.3) (2022-04-26)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.2...v0.11.3)

**Merged pull requests:**

- fix: create comption object after end marker
  [\#3866](https://github.com/scalameta/metals/pull/3866)
  ([dos65](https://github.com/dos65))
- feature: sbt-BSP - enable java-semanticdb plugin
  [\#3861](https://github.com/scalameta/metals/pull/3861)
  ([dos65](https://github.com/dos65))
- Use bloop.json instead of .jvmopts for Bloop memory properties
  [\#3864](https://github.com/scalameta/metals/pull/3864)
  ([tgodzik](https://github.com/tgodzik))
- handles the removal of jvm properties case
  [\#3863](https://github.com/scalameta/metals/pull/3863)
  ([zmerr](https://github.com/zmerr))
- Properly infer types for unapply signature help
  [\#3862](https://github.com/scalameta/metals/pull/3862)
  ([tgodzik](https://github.com/tgodzik))
- Add semantic db info display on jar files
  [\#3843](https://github.com/scalameta/metals/pull/3843)
  ([Arthurm1](https://github.com/Arthurm1))
- correct handling of end marker relocation on extract member
  [\#3847](https://github.com/scalameta/metals/pull/3847)
  ([zmerr](https://github.com/zmerr))
- Fix breakpoints in virtual docs
  [\#3846](https://github.com/scalameta/metals/pull/3846)
  ([kpodsiad](https://github.com/kpodsiad))
- Update scalafmt-core to 3.5.2
  [\#3858](https://github.com/scalameta/metals/pull/3858)
  ([scala-steward](https://github.com/scala-steward))
- Mark CancelCompileLspSuite as flaky
  [\#3857](https://github.com/scalameta/metals/pull/3857)
  ([tgodzik](https://github.com/tgodzik))
- fix: infer jdk without jdk in name
  [\#3856](https://github.com/scalameta/metals/pull/3856)
  ([kpodsiad](https://github.com/kpodsiad))
- Add classpath to CFR decompile
  [\#3844](https://github.com/scalameta/metals/pull/3844)
  ([Arthurm1](https://github.com/Arthurm1))
- fix: Make signature help work properly with a non-tuple result
  [\#3849](https://github.com/scalameta/metals/pull/3849)
  ([tgodzik](https://github.com/tgodzik))
- bloop jvm settings
  [\#3746](https://github.com/scalameta/metals/pull/3746)
  ([zmerr](https://github.com/zmerr))
- Remove some redundant collection conversions
  [\#3848](https://github.com/scalameta/metals/pull/3848)
  ([Duhemm](https://github.com/Duhemm))
- Update scala-xml to 2.1.0
  [\#3839](https://github.com/scalameta/metals/pull/3839)
  ([scala-steward](https://github.com/scala-steward))
- fix: show test explorer misconfigurations
  [\#3828](https://github.com/scalameta/metals/pull/3828)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: Register extension parameter symbols by mtags
  [\#3814](https://github.com/scalameta/metals/pull/3814)
  ([tanishiking](https://github.com/tanishiking))
- Update scalafix-interfaces to 0.10.0
  [\#3842](https://github.com/scalameta/metals/pull/3842)
  ([scala-steward](https://github.com/scala-steward))
- Update scalameta, semanticdb-scalac, ... to 4.5.3
  [\#3841](https://github.com/scalameta/metals/pull/3841)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.5.8
  [\#3838](https://github.com/scalameta/metals/pull/3838)
  ([scala-steward](https://github.com/scala-steward))
- Update undertow-core to 2.2.17.Final
  [\#3837](https://github.com/scalameta/metals/pull/3837)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt-scalafix, scalafix-interfaces to 0.10.0
  [\#3832](https://github.com/scalameta/metals/pull/3832)
  ([scala-steward](https://github.com/scala-steward))
- Update bloop-config, bloop-launcher to 1.4.13-75-f9d1bef5
  [\#3831](https://github.com/scalameta/metals/pull/3831)
  ([scala-steward](https://github.com/scala-steward))
- fix: (Scala3) Don't navigate to enclosing symbols on go-to-definition if cursor is not on symbol
  [\#3807](https://github.com/scalameta/metals/pull/3807)
  ([tanishiking](https://github.com/tanishiking))
- Update scalafmt-dynamic to 3.4.3
  [\#3840](https://github.com/scalameta/metals/pull/3840)
  ([scala-steward](https://github.com/scala-steward))
- Update ujson to 1.6.0
  [\#3835](https://github.com/scalameta/metals/pull/3835)
  ([scala-steward](https://github.com/scala-steward))
- test: Add test for completions of matchtype / higher-kinded type
  [\#3821](https://github.com/scalameta/metals/pull/3821)
  ([tanishiking](https://github.com/tanishiking))
- Update mill-contrib-testng to 0.10.3
  [\#3834](https://github.com/scalameta/metals/pull/3834)
  ([scala-steward](https://github.com/scala-steward))
- Update h2 to 2.1.212
  [\#3833](https://github.com/scalameta/metals/pull/3833)
  ([scala-steward](https://github.com/scala-steward))
- Fix formatting after last merge
  [\#3827](https://github.com/scalameta/metals/pull/3827)
  ([tgodzik](https://github.com/tgodzik))
- fix: Rename extension parameter in Scala3
  [\#3800](https://github.com/scalameta/metals/pull/3800)
  ([tanishiking](https://github.com/tanishiking))
- Bump Bloop with a fix for #3766
  [\#3826](https://github.com/scalameta/metals/pull/3826)
  ([tgodzik](https://github.com/tgodzik))
- fix: unblock release workflow
  [\#3825](https://github.com/scalameta/metals/pull/3825)
  ([dos65](https://github.com/dos65))
- Add support for Scala 3.1.2 and 3.1.3-RC2
  [\#3822](https://github.com/scalameta/metals/pull/3822)
  ([tgodzik](https://github.com/tgodzik))
- fix release script
  [\#3823](https://github.com/scalameta/metals/pull/3823)
  ([dos65](https://github.com/dos65))
- fix: handle backticked names in inffered-type
  [\#3791](https://github.com/scalameta/metals/pull/3791)
  ([dos65](https://github.com/dos65))
- bloop - bump version
  [\#3818](https://github.com/scalameta/metals/pull/3818)
  ([dos65](https://github.com/dos65))
- docs: add common workflow to contributing guide
  [\#3816](https://github.com/scalameta/metals/pull/3816)
  ([kpodsiad](https://github.com/kpodsiad))
- Remove -release option from the Scala 3 PC
  [\#3812](https://github.com/scalameta/metals/pull/3812)
  ([tgodzik](https://github.com/tgodzik))
- fix: uri encoding
  [\#3795](https://github.com/scalameta/metals/pull/3795)
  ([kpodsiad](https://github.com/kpodsiad))
- docs overview: mention editor pages for installation (#3796)
  [\#3811](https://github.com/scalameta/metals/pull/3811)
  ([juhp](https://github.com/juhp))
- Log BSP server stderr
  [\#3789](https://github.com/scalameta/metals/pull/3789)
  ([alexarchambault](https://github.com/alexarchambault))
- Support MatchType in SemanticdbTreePrinter
  [\#3787](https://github.com/scalameta/metals/pull/3787)
  ([tanishiking](https://github.com/tanishiking))
- [Scala3] Fix hover on interpolator-apply
  [\#3806](https://github.com/scalameta/metals/pull/3806)
  ([tanishiking](https://github.com/tanishiking))
- Remove broken nightly version
  [\#3805](https://github.com/scalameta/metals/pull/3805)
  ([tgodzik](https://github.com/tgodzik))
- Update scalafmt-core to 3.4.3
  [\#3802](https://github.com/scalameta/metals/pull/3802)
  ([scala-steward](https://github.com/scala-steward))
- refactor: add a header object to json doctor output
  [\#3803](https://github.com/scalameta/metals/pull/3803)
  ([ckipp01](https://github.com/ckipp01))
- docs: Neovim and Plug clarification
  [\#3799](https://github.com/scalameta/metals/pull/3799)
  ([tacerus](https://github.com/tacerus))
- docs: add in docs about doctorVisibilityDidChange
  [\#3798](https://github.com/scalameta/metals/pull/3798)
  ([ckipp01](https://github.com/ckipp01))
- fix: disable not supported Wconf for scala3
  [\#3793](https://github.com/scalameta/metals/pull/3793)
  ([dos65](https://github.com/dos65))
- [Scala3] Don't show a tooltip on hover if cursor is not on a symbol
  [\#3792](https://github.com/scalameta/metals/pull/3792)
  ([tanishiking](https://github.com/tanishiking))
- chore(deps): bump actions/checkout from 2 to 3
  [\#3782](https://github.com/scalameta/metals/pull/3782)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- Update mdoc-interfaces to 2.3.2
  [\#3781](https://github.com/scalameta/metals/pull/3781)
  ([scala-steward](https://github.com/scala-steward))
- Update scalameta, semanticdb-scalac, ... to 4.5.1
  [\#3780](https://github.com/scalameta/metals/pull/3780)
  ([scala-steward](https://github.com/scala-steward))
- Try to fix flaky test by adding a delay in compilation
  [\#3765](https://github.com/scalameta/metals/pull/3765)
  ([tanishiking](https://github.com/tanishiking))
- Update mill-contrib-testng to 0.10.2
  [\#3775](https://github.com/scalameta/metals/pull/3775)
  ([scala-steward](https://github.com/scala-steward))
- Update mdoc, mdoc-interfaces, sbt-mdoc to 2.3.2
  [\#3779](https://github.com/scalameta/metals/pull/3779)
  ([scala-steward](https://github.com/scala-steward))
- Update file-tree-views to 2.1.9
  [\#3777](https://github.com/scalameta/metals/pull/3777)
  ([scala-steward](https://github.com/scala-steward))
- Update bloop-config, bloop-launcher to 1.4.13-55-1fc97fae
  [\#3774](https://github.com/scalameta/metals/pull/3774)
  ([scala-steward](https://github.com/scala-steward))
- Update pprint to 0.7.3
  [\#3776](https://github.com/scalameta/metals/pull/3776)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.5.5
  [\#3778](https://github.com/scalameta/metals/pull/3778)
  ([scala-steward](https://github.com/scala-steward))
- feat: add java version and Metals version to doctor
  [\#3772](https://github.com/scalameta/metals/pull/3772)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: nightly script
  [\#3773](https://github.com/scalameta/metals/pull/3773)
  ([dos65](https://github.com/dos65))
- fix: munit test discovery when there is no package
  [\#3770](https://github.com/scalameta/metals/pull/3770)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: do not call `doctor.check()` on each build target compilation end
  [\#3768](https://github.com/scalameta/metals/pull/3768)
  ([kpodsiad](https://github.com/kpodsiad))
- chore: reduce size of build.sbt
  [\#3759](https://github.com/scalameta/metals/pull/3759)
  ([kpodsiad](https://github.com/kpodsiad))
- scala3 - exclude 3.2.0-NIGHTLY and align tests cases
  [\#3767](https://github.com/scalameta/metals/pull/3767)
  ([dos65](https://github.com/dos65))
- chore: enable `-Wunused`
  [\#3760](https://github.com/scalameta/metals/pull/3760)
  ([kpodsiad](https://github.com/kpodsiad))
- Fix issues with non alpha numeric build target names
  [\#3756](https://github.com/scalameta/metals/pull/3756)
  ([tgodzik](https://github.com/tgodzik))
- Introduce uses of buildTarget/inverseSources
  [\#3752](https://github.com/scalameta/metals/pull/3752)
  ([Duhemm](https://github.com/Duhemm))
- add navigation from doctor to build target
  [\#3763](https://github.com/scalameta/metals/pull/3763)
  ([Arthurm1](https://github.com/Arthurm1))
- feat: add better munit support to the Test Explorer
  [\#3742](https://github.com/scalameta/metals/pull/3742)
  ([kpodsiad](https://github.com/kpodsiad))
- dep: bump release candidate version to 3.1.2-RC3
  [\#3753](https://github.com/scalameta/metals/pull/3753)
  ([ckipp01](https://github.com/ckipp01))
- fix: specify groups for new test suites
  [\#3749](https://github.com/scalameta/metals/pull/3749)
  ([dos65](https://github.com/dos65))
- fix: create `new scala file` with a single NL
  [\#3745](https://github.com/scalameta/metals/pull/3745)
  ([dos65](https://github.com/dos65))
- Show better unapply signature help
  [\#3738](https://github.com/scalameta/metals/pull/3738)
  ([tgodzik](https://github.com/tgodzik))
- support for creating companion objects
  [\#3709](https://github.com/scalameta/metals/pull/3709)
  ([zmerr](https://github.com/zmerr))
- fix: return an error when trying to decode tasty in Scala 2
  [\#3739](https://github.com/scalameta/metals/pull/3739)
  ([ckipp01](https://github.com/ckipp01))
- Refactor Ammonite support…
  [\#3550](https://github.com/scalameta/metals/pull/3550)
  ([alexarchambault](https://github.com/alexarchambault))
- fix: don't discover already discovered tests
  [\#3737](https://github.com/scalameta/metals/pull/3737)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: typo in workflow script
  [\#3735](https://github.com/scalameta/metals/pull/3735)
  ([dos65](https://github.com/dos65))
- fix: do not fail nightly check job if there are no new releases
  [\#3734](https://github.com/scalameta/metals/pull/3734)
  ([dos65](https://github.com/dos65))
- feat: add more feedback to user when starting a debug session
  [\#3731](https://github.com/scalameta/metals/pull/3731)
  ([ckipp01](https://github.com/ckipp01))
- Remove jackson since it's no used in the code
  [\#3733](https://github.com/scalameta/metals/pull/3733)
  ([tgodzik](https://github.com/tgodzik))
- Prefer Scala 2.13 version over older versions
  [\#3728](https://github.com/scalameta/metals/pull/3728)
  ([tgodzik](https://github.com/tgodzik))
- docs: small update to contributing docs
  [\#3732](https://github.com/scalameta/metals/pull/3732)
  ([ckipp01](https://github.com/ckipp01))
- bump: bloop
  [\#3729](https://github.com/scalameta/metals/pull/3729)
  ([kpodsiad](https://github.com/kpodsiad))
- feat: implement test selection request for sbt
  [\#3678](https://github.com/scalameta/metals/pull/3678)
  ([kpodsiad](https://github.com/kpodsiad))
- Warn users properly when wrong release flag was used
  [\#3703](https://github.com/scalameta/metals/pull/3703)
  ([tgodzik](https://github.com/tgodzik))
- fix(doctor): ensure compilationStatus is in the json payload
  [\#3725](https://github.com/scalameta/metals/pull/3725)
  ([ckipp01](https://github.com/ckipp01))
- Add `generated` info to `Source Directories `section of `Display build target info`
  [\#3720](https://github.com/scalameta/metals/pull/3720)
  ([tanishiking](https://github.com/tanishiking))
- fix: ensure user gets warned of workspace error when running with lens
  [\#3723](https://github.com/scalameta/metals/pull/3723)
  ([ckipp01](https://github.com/ckipp01))
- feat: add compilation status to the doctor
  [\#3710](https://github.com/scalameta/metals/pull/3710)
  ([kpodsiad](https://github.com/kpodsiad))
- Bump Bloop to the newest version with updated Zinc
  [\#3706](https://github.com/scalameta/metals/pull/3706)
  ([tgodzik](https://github.com/tgodzik))
- fix: Ensure the "no run or test" message is shown to user.
  [\#3721](https://github.com/scalameta/metals/pull/3721)
  ([ckipp01](https://github.com/ckipp01))
- Update mill-contrib-testng to 0.10.1
  [\#3714](https://github.com/scalameta/metals/pull/3714)
  ([scala-steward](https://github.com/scala-steward))
- Update pprint to 0.7.2
  [\#3715](https://github.com/scalameta/metals/pull/3715)
  ([scala-steward](https://github.com/scala-steward))
- Exclude generated source items from FileWatcher (sbt BSP)
  [\#3694](https://github.com/scalameta/metals/pull/3694)
  ([tanishiking](https://github.com/tanishiking))
- Update flyway-core to 8.5.3
  [\#3717](https://github.com/scalameta/metals/pull/3717)
  ([scala-steward](https://github.com/scala-steward))
- Update scribe, scribe-file, scribe-slf4j to 3.8.2
  [\#3716](https://github.com/scalameta/metals/pull/3716)
  ([scala-steward](https://github.com/scala-steward))
- Fallback to current file contents always
  [\#3711](https://github.com/scalameta/metals/pull/3711)
  ([tgodzik](https://github.com/tgodzik))
- Move gabro to "past maintainers" in README
  [\#3708](https://github.com/scalameta/metals/pull/3708)
  ([gabro](https://github.com/gabro))
- refactor: use "using" instead of "implicit" in Scala 3 signature help.
  [\#3707](https://github.com/scalameta/metals/pull/3707)
  ([ckipp01](https://github.com/ckipp01))
- fix: scala3 completion - fix isses with code indented using tabs
  [\#3702](https://github.com/scalameta/metals/pull/3702)
  ([dos65](https://github.com/dos65))
- fix: add test case for #3625
  [\#3704](https://github.com/scalameta/metals/pull/3704)
  ([tgodzik](https://github.com/tgodzik))
- Fix completions on dot in expression evaluator
  [\#3705](https://github.com/scalameta/metals/pull/3705)
  ([tgodzik](https://github.com/tgodzik))
- Fix issues with Bloop snapshot versions
  [\#3699](https://github.com/scalameta/metals/pull/3699)
  ([tgodzik](https://github.com/tgodzik))
- fix: do not decode every URI when invoking `toAbsolutePath`
  [\#3701](https://github.com/scalameta/metals/pull/3701)
  ([kpodsiad](https://github.com/kpodsiad))
- docs: make release instruction more structured
  [\#3700](https://github.com/scalameta/metals/pull/3700)
  ([kpodsiad](https://github.com/kpodsiad))
-  Fix DefinitionCrossLspSuite
  [\#3692](https://github.com/scalameta/metals/pull/3692)
  ([tgodzik](https://github.com/tgodzik))
- chore(docs): get snapshot version for docs from 2.13 now
  [\#3693](https://github.com/scalameta/metals/pull/3693)
  ([ckipp01](https://github.com/ckipp01))
- Bump release candidate version to 3.1.2-RC2
  [\#3697](https://github.com/scalameta/metals/pull/3697)
  ([tgodzik](https://github.com/tgodzik))
- ci: update Metals version in mtags release workflow
  [\#3695](https://github.com/scalameta/metals/pull/3695)
  ([dos65](https://github.com/dos65))
- fix: adjust tests for 3.2.0-NIGHTLY
  [\#3687](https://github.com/scalameta/metals/pull/3687)
  ([dos65](https://github.com/dos65))
- Fix named arg completion when parameter contains $
  [\#3686](https://github.com/scalameta/metals/pull/3686)
  ([tanishiking](https://github.com/tanishiking))
- Fix flaky WorkspaceSymbolLspSuite test
  [\#3691](https://github.com/scalameta/metals/pull/3691)
  ([tgodzik](https://github.com/tgodzik))
- chore: migrate Metals to 2.13
  [\#3631](https://github.com/scalameta/metals/pull/3631)
  ([ckipp01](https://github.com/ckipp01))
- docs: add release notes
  [\#3682](https://github.com/scalameta/metals/pull/3682)
  ([kpodsiad](https://github.com/kpodsiad))
