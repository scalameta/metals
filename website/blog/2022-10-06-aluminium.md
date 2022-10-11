---
author: Tomasz Godzik
title: Metals v0.11.9 - Aluminium
authorURL: https://twitter.com/TomekGodzik
authorImageURL: https://github.com/tgodzik.png
---

We're happy to announce the release of Metals v0.11.9, which brings some of the
final missing features in Scala 3 and greatly improves its performance. We also
had quite a number of contributions in this release and I wanted to thank all
the new contributors for their great work!

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">203</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">167</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">14</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">37</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">21</td>
  </tr>
</tbody>
</table>

For full details: [https://github.com/scalameta/metals/milestone/53?closed=1](https://github.com/scalameta/metals/milestone/53?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Adjust mismatched type](#adjust-mismatched-type)
- [[Scala 3] Ammonite completions](#ammonite-completions-for-scala-3)
- [Better Scala CLI support](#better-scala-cli-support)
- [[Scala 3] Auto import symbols in string interpolation](#scala-3-auto-import-symbols-in-string-interpolation)
- [[Scala 3] Add autofill named arguments completion](#scala-3-add-autofill-named-arguments-completion)
- [[Scala 3] Improved performance](#scala-3-improved-performance)
- [Make document highlight more reliable](#make-documenthighlight-more-reliable)
- [[Scala 3] Add completions for case and match](#scala-3-add-completions-for-case-and-match)
- ["Extract Method" code action](#extract-method-code-action)
- [[Scala 2] Add Completions for ivy Imports in Ammonite scripts and worksheets](#scala-2-add-completions-for-ivy-imports-in-ammonite-scripts-and-worksheets)
- [Call hierarchy](#call-hierarchy)
- [[Scala 3] Enable fewer braces for Scala 3 nightly versions](#scala-3-enable-fewer-braces-for-scala-3-nightly-versions)
- Add support for Scala 3.2.0, 2.12.17, 2.13.9 and RC versions of 3.2.1

## Adjust mismatched type

Until recently it was only possible to add the type to a definition that didn't
have it defined. However, it's also quite common for users to change the
returned type of the body of a function and in that case any adjustments had to
be made manually. Now, whenever the compiler shows type mismatch error Metals
should be able to adjust the type for you automatically.

![adjust-type](https://i.imgur.com/R7z7JNf.gif)

As this feature is quite new and might still require some tinkering, please
report any issues you find.

## Ammonite completions for Scala 3

Until recently Metals was only offering `$file` completions for Scala 2, but
thanks to [zmerr](https://github.com/zmerr) it's also possible to get them in
Scala 3.

For more details take a look at the previous
[announcement](https://scalameta.org/metals/blog/2020/07/01/lithium/#ammonite-support)

## Better Scala CLI support

[ScalaCLI](https://scala-cli.virtuslab.org/) is a tool developed by VirtusLab in
an effort to create a simple experience of using Scala for all its users. It has
a lot of nice features, but one of the main ones is
[the possibility to use it in scripts](https://scala-cli.virtuslab.org/docs/guides/scripts).
Thanks to [alexarchambault](https://github.com/alexarchambault), who is also
leading the effort on ScalaCLI, Metals now supports ScalaCLI scripts along with
the Ammonite ones.

![scala-cli](https://i.imgur.com/ghR1Src.gif)

Whenever, Metals will be opened on a file with `*.sc` extension (but not
`*.worksheet.sc`), users will be prompted to choose which tool they want to use
for the script.

Similar to Ammonite, support for it can also be turned on manually with
`Metals: Start Scala CLI BSP server` and stopped with
`Metals: Stop Scala CLI BSP server`. These commands can be used for more fine
grained control when to turn on or of scripting support. This is especially
useful since ScalaCLI supports will run an additional BSP server running
underneath and that can take up some additional resources.

If the script is in a dedicated folder, by default we will treat all the scripts
and scala files in that directory as ones that can be used together. So you
would be able to import method and classes from those files. However, if the
script is contained within a directory that also contains other sources, that
script will be treated as a standalone one in order to avoid flaky compilation
errors coming from normal files in the workspace.

## [Scala 3] Auto import symbols in string interpolation

Previously, the only suggestions for string interpolations were coming from the
currently available symbols in scope. This meant that if you wanted to import
something from another package, you would need to do it manually.

This problem is now resolved. Users can easily get such symbols automatically
imported, which creates a seamless more workflow.

![auto-import](https://i.imgur.com/COKtciq.gif)

These completions will also allow you to automatically import extension methods.

The feature is already available for Scala 2.

## [Scala 3] Add autofill named arguments completion

Thanks to great work by [jkciesluk](https://github.com/jkciesluk) Scala 3
completions will also show an `autofill all` completion item, which will try to
fill in all the arguments of the current method using the symbol in scope or
with `???` if no symbols match. Users can later tabulate over the different
proposal to adjust them.

![auto-fill](https://i.imgur.com/tZTKnSw.gif)

The feature was already available for Scala 2.

## [Scala 3] Improved performance

We've noticed that previous versions of Metals could cause CPU spikes for anyone
using Scala 3. To address this, we managed to include a couple performance
improvements in this release. This will improve several editor features such as
hover, completions, document highlight, and some code actions.

For more details, see the pull requests.

- [Don't run InteractiveDriver.run if the content didn't "change" by tanishiking · Pull Request #4225 · scalameta/metals](https://github.com/scalameta/metals/pull/4225)
- [fix: \[Scala3\] Don't compute docs on textDocument/completion by tanishiking · Pull Request #4396 · scalameta/metals](https://github.com/scalameta/metals/pull/4396)

Thanks to [tanishiking](https://github.com/tanishiking)) for investigation and
his great fixes.

## Make documentHighlight more reliable

Document highlight allows users to see all occurrences of the symbol currently
under the cursor in a single file. This was one of the first
[features](https://scalameta.org/metals/blog/2019/04/12/mercury#document-highlight)
developed by me for Metals and previously it would only work in a fully compiled
workspace. This means that if users have errors, some of the occurrences might
not be highlighted.

Since the feature only works within a single file, this was a perfect candidate
for turning into a more interactive feature, which would use the Scala
presentation compiler already used in such features as hovers or completions.
Thanks to this change document highlight should work even if your code doesn't
compile.

![document-highlight](https://i.imgur.com/0uhc9P5.gif)

This should also make it easier for us to fix any issue that might pop up and it
has already improved support for locally defined classes.

## [Scala 3] Add completions for case and match

Metals provides additional completions whenever users write match clauses to
automatically fill either a single case with one of the possible subtypes or an
exhaustive completion with all of them. This was previously not available for
Scala 3, but thanks to ([jkciesluk](https://github.com/jkciesluk)) users can now
benefit from this feature in their Scala 3 codebases.

![exhaustive](https://i.imgur.com/6wynpRq.gif)

The Scala 3 version will additionally work with the Scala enums as well as offer
completions for simple cases with one possible type, which might be useful to
access values inside it. Moreover, exhaustive completions will also be offered
within partial function, for example in a `.map{}`.

## Extract Method code action

Another great feature contributed by [jkciesluk](https://github.com/jkciesluk)
is the possibility to extract arbitrary parts of code into a separate function.
It will also add any values unavailable in the selected scope as parameters in
the new function.

![extract-method](https://i.imgur.com/VMXLKPg.gif)

The code action will currently not turn any methods or classes into parameters
even if they is not available in the scope into which the code is being
extracted.

## [Scala 2] Add Completions for ivy Imports in Ammonite scripts and worksheets

Both in worksheets and in ammonite scripts you can add dependencies using
imports such as:

```scala
import $$ivy.`io.circe::circe-core:0.14.2`
```

which will bring in the `circe-core` dependency. Starting in this version it
will be possible to auto complete group ids, artifact names and their versions.

![import-dep](https://i.imgur.com/TWgIIVp.gif)

Thanks to [LaurenceWarne](https://github.com/LaurenceWarne) for the great
feature!

We are also working on enabling this feature in Mill, sbt, ScalaCLI and later
for Scala 3.

## Call hierarchy

This release brings about
[call hierarchy support](https://microsoft.github.io/language-server-protocol/specifications/specification-3-16/#textDocument_prepareCallHierarchy),
which is an LSP feature that enables users to see which method are invoked in a
current method or which methods invoke the current one.

![call-hierarchy-dep](https://i.imgur.com/1lTYFmu.gif)

This non trivial feature was brought to you by
[riiswa](https://github.com/riiswa), thanks for the great work!

A current limitation is that it will only work for methods within the workspace.

## [Scala 3] Enable fewer braces for Scala 3 nightly versions

Fewer braces is an experimental Scala 3 feature that enables users to drop
braces in more places than the default, for example in function invocations.
Previously, Metals would show it as error, but since this version fewer braces
option will be recognized by default for the Scala 3 nightly versions.

Read more about fewer braces
[here](https://docs.scala-lang.org/scala3/reference/experimental/fewer-braces.html).

More work is required to enable auto formatting with this option and will be
worked on in the near future.

## Various

This release also features a number of smaller fixes and improvements, some of
the user facing ones are mentioned here:

- feature: Include anon classes (`val x = new Trait {}`) in 'find all
  implementations' [kpodsiad](https://github.com/kpodsiad)
- feature: Implement basic Text Explorer support for scalatest
  [kpodsiad](https://github.com/kpodsiad) and allow running single tests.
- feature: Better mill-bsp semanticdb support
  [ckipp01](https://github.com/ckipp01)
- feature: Allow extract value code action when using `new` keyword
  [jkciesluk](https://github.com/jkciesluk)
- feature: Provide ConvertToNamedArg code action for constructor invocations
  [jkciesluk](https://github.com/jkciesluk))
- feature: Add Analyze Stacktrace command to metals menu
- feature: Improved completions in type positions
- feature: Add completion for extends keyword after classes/traits/objects/enums
- improvement: Don't show misconfigured test message
- bugfix: Add backticks where needed in "convert to named arguments" [kubukoz](https://github.com/kubukoz)
- bugfix: Try to download semanticdb even if it might not be supported
- bugfix: Properly write constant types in signatures
- bugfix: Allow to add jvmopts and env variables when running tests
- bugfix: Compile project on file deletion [PeuTit](https://github.com/PeuTit)
- bugfix: Limit running multiple import build
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Request import build after restart even if notification was dismissed
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Properly cancel all current compilations
- bugfix: Remove worksheet diagnostics on close
- bugfix: [Scala 3] Don't throw exception if template ends at end of file
- bugfix: [Scala 2] Fix argument completions in nested method invocation
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: [Scala 2] Fix signature help when named params are present
- bugfix: Don't show preparing PC message when in fact it was already downloaded
- bugfix: Properly restart worksheet presentation compiler to show the newest
  completions from the workspace after the compilation finished
- bugfix: [Scala 3] Fix implement-all members for anonymous class
  [tanishiking](https://github.com/tanishiking)
- bugfix: Allow to run/debug in ScalaCli when it was started from Metals without
  `.bsp`
- bugfix: Show both companion object and case class when searching of definition
  for synthetic apply
- bugfix: [Scala 3] Properly filter symbols if they don't exist in the code
  anymore [jkciesluk](https://github.com/jkciesluk)
- bugfix: don't break using-directives by an auto-import for `.sc` in scala-cli
  [dos65](https://github.com/dos65) For a full list take a look at the list of
  PRs below.
- bugfix: [Scala 3] - provide a correct defitions for symbols from
  `stdLibPatched` package [dos65](https://github.com/dos65)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.8..v0.11.9
Tomasz Godzik
Waris Radji
Rikito Taniguchi
Vadim Chelyshov
scalameta-bot
Jakub Ciesluk
Jakub Kozłowski
Kamil Podsiadło
Chris Kipp
Laurence Warne
Titouan
Alexandre Archambault
Anton
Ina Zimmer
dependabot[bot]
```

## Merged PRs

## [v0.11.9](https://github.com/scalameta/metals/tree/v0.11.9) (2022-10-06)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.8...v0.11.9)

**Merged pull requests:**

- chore: Update Bloop to 1.5.4
  [\#4480](https://github.com/scalameta/metals/pull/4480)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Properly import extension methods in string interpolation
  [\#4479](https://github.com/scalameta/metals/pull/4479)
  ([tgodzik](https://github.com/tgodzik))
- Import enum owner in case comp and in java enum match
  [\#4478](https://github.com/scalameta/metals/pull/4478)
  ([jkciesluk](https://github.com/jkciesluk))
- Fix: Remove multiple enum owner imports
  [\#4477](https://github.com/scalameta/metals/pull/4477)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: Always specify enum owner
  [\#4475](https://github.com/scalameta/metals/pull/4475)
  ([tgodzik](https://github.com/tgodzik))
- Add backticks where needed in "convert to named arguments"
  [\#4470](https://github.com/scalameta/metals/pull/4470)
  ([kubukoz](https://github.com/kubukoz))
- build(deps): Update ammonite-util from 2.5.4-33-0af04a5b to 2.5.4-34-1c7b3c38
  [\#4472](https://github.com/scalameta/metals/pull/4472)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Fix issues when renaming variables
  [\#4458](https://github.com/scalameta/metals/pull/4468)
  ([tgodzik](https://github.com/tgodzik))
- feature: Enable fewer braces for Scala 3 nightly versions
  [\#4458](https://github.com/scalameta/metals/pull/4458)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalameta, semanticdb-scalac, ... from 4.5.13 to 4.6.0
  [\#4463](https://github.com/scalameta/metals/pull/4463)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.14 to 0.1.15
  [\#4464](https://github.com/scalameta/metals/pull/4464)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.3.1 to 9.4.0
  [\#4460](https://github.com/scalameta/metals/pull/4460)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc, sbt-mdoc from 2.3.4 to 2.3.5
  [\#4461](https://github.com/scalameta/metals/pull/4461)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc-interfaces from 2.3.3 to 2.3.5
  [\#4462](https://github.com/scalameta/metals/pull/4462)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Remove old workaround
  [\#4459](https://github.com/scalameta/metals/pull/4459)
  ([tgodzik](https://github.com/tgodzik))
- fix: bunch of fixes for ScalaCli scripts import
  [\#4455](https://github.com/scalameta/metals/pull/4455)
  ([dos65](https://github.com/dos65))
- refactor: Don't log "navigation doesn't work" if fallback to compiler based
  navigation [\#4457](https://github.com/scalameta/metals/pull/4457)
  ([tanishiking](https://github.com/tanishiking))
- feature: Don't show misconfigured test message
  [\#4454](https://github.com/scalameta/metals/pull/4454)
  ([tgodzik](https://github.com/tgodzik))
- chore: Update Bloop to newest version
  [\#4453](https://github.com/scalameta/metals/pull/4453)
  ([tgodzik](https://github.com/tgodzik))
- fix: Try to download semanticdb even if it might not be supported
  [\#4450](https://github.com/scalameta/metals/pull/4450)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Fix document highlight in for comprehensions
  [\#4448](https://github.com/scalameta/metals/pull/4448)
  ([tgodzik](https://github.com/tgodzik))
- refactor: [scala3] findSuffix to return a data structure so we can tell what
  kinds of suffix will be added to the edit.
  [\#4412](https://github.com/scalameta/metals/pull/4412)
  ([tanishiking](https://github.com/tanishiking))
- Offer exhaustive completions for map and other methods
  [\#4415](https://github.com/scalameta/metals/pull/4415)
  ([jkciesluk](https://github.com/jkciesluk))
- feature: Add Ammonite completions to worksheets
  [\#4443](https://github.com/scalameta/metals/pull/4443)
  ([tgodzik](https://github.com/tgodzik))
- bufix: Properly write constant types in signatures
  [\#4442](https://github.com/scalameta/metals/pull/4442)
  ([tgodzik](https://github.com/tgodzik))
- chore: Allow to run tests with Scala nightlies
  [\#4435](https://github.com/scalameta/metals/pull/4435)
  ([tgodzik](https://github.com/tgodzik))
- chore: Update Scala version used by sbt
  [\#4440](https://github.com/scalameta/metals/pull/4440)
  ([tgodzik](https://github.com/tgodzik))
- move isImportInProgress to runUnconditionally
  [\#4439](https://github.com/scalameta/metals/pull/4439)
  ([jkciesluk](https://github.com/jkciesluk))
- Auto-Import After Ammonite Headers
  [\#4436](https://github.com/scalameta/metals/pull/4436)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- Don't show private members in case completions
  [\#4431](https://github.com/scalameta/metals/pull/4431)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update scalafix-interfaces from 0.10.2 to 0.10.3
  [\#4437](https://github.com/scalameta/metals/pull/4437)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc, mdoc-interfaces, sbt-mdoc from 2.3.3 to 2.3.4
  [\#4438](https://github.com/scalameta/metals/pull/4438)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Properly cancel all current compilations
  [\#4421](https://github.com/scalameta/metals/pull/4421)
  ([tgodzik](https://github.com/tgodzik))
- chore: Bump Scala versions for Ammonite
  [\#4433](https://github.com/scalameta/metals/pull/4433)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Remove all cancallables on finish
  [\#4432](https://github.com/scalameta/metals/pull/4432)
  ([tgodzik](https://github.com/tgodzik))
- Scala 2.13.9 [\#4414](https://github.com/scalameta/metals/pull/4414)
  ([dos65](https://github.com/dos65))
- build(deps): Update flyway-core from 9.3.0 to 9.3.1
  [\#4427](https://github.com/scalameta/metals/pull/4427)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier from 2.1.0-M6 to 2.1.0-M7
  [\#4426](https://github.com/scalameta/metals/pull/4426)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ammonite-util from 2.5.4-22-4a9e6989 to 2.5.4-33-0af04a5b
  [\#4425](https://github.com/scalameta/metals/pull/4425)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-scalafix, scalafix-interfaces from 0.10.2 to 0.10.3
  [\#4424](https://github.com/scalameta/metals/pull/4424)
  ([scalameta-bot](https://github.com/scalameta-bot))
- ci(Mergify): Don't check the number of check-success
  [\#4429](https://github.com/scalameta/metals/pull/4429)
  ([tanishiking](https://github.com/tanishiking))
- feat: extends keyword completion
  [\#4416](https://github.com/scalameta/metals/pull/4416)
  ([tanishiking](https://github.com/tanishiking))
- refactor: Change code action ids sets to val
  [\#4418](https://github.com/scalameta/metals/pull/4418)
  ([tgodzik](https://github.com/tgodzik))
- refactor: Make it easier to define commands to run in code actions
  [\#4413](https://github.com/scalameta/metals/pull/4413)
  ([tgodzik](https://github.com/tgodzik))
- Feature/call hierarchy [\#4115](https://github.com/scalameta/metals/pull/4115)
  ([riiswa](https://github.com/riiswa))
- bugfix Try to fix flaky import tests
  [\#4408](https://github.com/scalameta/metals/pull/4408)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalafix-interfaces from 0.10.1 to 0.10.2
  [\#4400](https://github.com/scalameta/metals/pull/4400)
  ([scalameta-bot](https://github.com/scalameta-bot))
- ci(Mergify): configuration update / use squash
  [\#4409](https://github.com/scalameta/metals/pull/4409)
  ([tanishiking](https://github.com/tanishiking))
- ci(Mergify): configuration update
  [\#4407](https://github.com/scalameta/metals/pull/4407)
  ([tanishiking](https://github.com/tanishiking))
- fix: Scala3 properly inverse semanticdb type symbol
  [\#4383](https://github.com/scalameta/metals/pull/4383)
  ([dos65](https://github.com/dos65))
- bugfix: Allow to add jvmopts and env variables when running tests
  [\#4393](https://github.com/scalameta/metals/pull/4393)
  ([tgodzik](https://github.com/tgodzik))
- Ivy Completions Dash Fix
  [\#4405](https://github.com/scalameta/metals/pull/4405)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- ci(Mergify): configuration update
  [\#4404](https://github.com/scalameta/metals/pull/4404)
  ([tanishiking](https://github.com/tanishiking))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.12 to 0.1.14
  [\#4403](https://github.com/scalameta/metals/pull/4403)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update xnio-nio from 3.8.7.Final to 3.8.8.Final
  [\#4401](https://github.com/scalameta/metals/pull/4401)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: [Scala3] Don't compute docs on textDocument/completion
  [\#4396](https://github.com/scalameta/metals/pull/4396)
  ([tanishiking](https://github.com/tanishiking))
- add 3.2.1-RC2 support [\#4392](https://github.com/scalameta/metals/pull/4392)
  ([dos65](https://github.com/dos65))
- bugfix: Fix document highlight for type projections and bind
  [\#4394](https://github.com/scalameta/metals/pull/4394)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Revert ammonite to 2.5.4-22-4a9e6989
  [\#4395](https://github.com/scalameta/metals/pull/4395)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix tests using circe for completions
  [\#4391](https://github.com/scalameta/metals/pull/4391)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update interface from 1.0.8 to 1.0.9
  [\#4390](https://github.com/scalameta/metals/pull/4390)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ammonite-util from 2.5.4-22-4a9e6989 to 2.5.4-26-9cd15abe
  [\#4388](https://github.com/scalameta/metals/pull/4388)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Always check if import is in progress
  [\#4384](https://github.com/scalameta/metals/pull/4384)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update sbt-scalafix, scalafix-interfaces from 0.10.1 to 0.10.2
  [\#4387](https://github.com/scalameta/metals/pull/4387)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: rename CompletionProvider
  [\#4385](https://github.com/scalameta/metals/pull/4385)
  ([dos65](https://github.com/dos65))
- chore: Add support for Scala 2.12.17
  [\#4382](https://github.com/scalameta/metals/pull/4382)
  ([tgodzik](https://github.com/tgodzik))
- Add cross tests for pattern only case completions, fix for typed case
  completions [\#4356](https://github.com/scalameta/metals/pull/4356)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: use proper semanticdb document for `.sc` in scala-cli
  [\#4359](https://github.com/scalameta/metals/pull/4359)
  ([dos65](https://github.com/dos65))
- Add Highlighting and Fix Formatting for Emacs Snippets in Documentation
  [\#4381](https://github.com/scalameta/metals/pull/4381)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- improvement: Change string actions to RefactorRewrite
  [\#4380](https://github.com/scalameta/metals/pull/4380)
  ([tgodzik](https://github.com/tgodzik))
- Compile project on file deletion
  [\#4377](https://github.com/scalameta/metals/pull/4377)
  ([PeuTit](https://github.com/PeuTit))
- Add Completions for ivy Imports (Ammonite)
  [\#4376](https://github.com/scalameta/metals/pull/4376)
  ([LaurenceWarne](https://github.com/LaurenceWarne))
- fix: scala3 - provide a correct defitions for symbols from `stdLibPatched`
  package [\#4370](https://github.com/scalameta/metals/pull/4370)
  ([dos65](https://github.com/dos65))
- Add Extract Method code action
  [\#4164](https://github.com/scalameta/metals/pull/4164)
  ([jkciesluk](https://github.com/jkciesluk))
- Request import build after restart
  [\#4373](https://github.com/scalameta/metals/pull/4373)
  ([jkciesluk](https://github.com/jkciesluk))
- Limit running multiple import build
  [\#4375](https://github.com/scalameta/metals/pull/4375)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update flyway-core from 9.2.3 to 9.3.0
  [\#4371](https://github.com/scalameta/metals/pull/4371)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ammonite-runner from 0.3.3 to 0.4.0
  [\#4363](https://github.com/scalameta/metals/pull/4363)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: Detect pull_request label event for auto-approve
  [\#4365](https://github.com/scalameta/metals/pull/4365)
  ([tanishiking](https://github.com/tanishiking))
- build(deps): Update ammonite-util from 2.5.4-19-cd76521f to 2.5.4-22-4a9e6989
  [\#4362](https://github.com/scalameta/metals/pull/4362)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.2.2 to 9.2.3
  [\#4364](https://github.com/scalameta/metals/pull/4364)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: type completions additonal fixes
  [\#4234](https://github.com/scalameta/metals/pull/4234)
  ([dos65](https://github.com/dos65))
- Change adding space after case completions
  [\#4353](https://github.com/scalameta/metals/pull/4353)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update qdox from 2.0.1 to 2.0.2
  [\#4344](https://github.com/scalameta/metals/pull/4344)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Auto approve PR by scalameta-bot
  [\#4346](https://github.com/scalameta/metals/pull/4346)
  ([tanishiking](https://github.com/tanishiking))
- Fix case completions for enum with params
  [\#4354](https://github.com/scalameta/metals/pull/4354)
  ([jkciesluk](https://github.com/jkciesluk))
- Add case completions for single case classes in Scala3
  [\#4351](https://github.com/scalameta/metals/pull/4351)
  ([jkciesluk](https://github.com/jkciesluk))
- Enable enum completions in pattern matching
  [\#4349](https://github.com/scalameta/metals/pull/4349)
  ([jkciesluk](https://github.com/jkciesluk))
- feature: Add completions for case and match for scala 3
  [\#4229](https://github.com/scalameta/metals/pull/4229)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: add scala 3.2.1-RC1
  [\#4347](https://github.com/scalameta/metals/pull/4347)
  ([dos65](https://github.com/dos65))
- Replace toLSP occurences by toLsp.
  [\#4331](https://github.com/scalameta/metals/pull/4331)
  ([riiswa](https://github.com/riiswa))
- build(deps): Update flyway-core from 9.1.6 to 9.2.2
  [\#4345](https://github.com/scalameta/metals/pull/4345)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: Add labels to scala-steward PRs
  [\#4337](https://github.com/scalameta/metals/pull/4337)
  ([tanishiking](https://github.com/tanishiking))
- feature: Add Analyze Stacktrace command to metals menu
  [\#4340](https://github.com/scalameta/metals/pull/4340)
  ([tgodzik](https://github.com/tgodzik))
- fix: scala-steward cron (again)
  [\#4342](https://github.com/scalameta/metals/pull/4342)
  ([tanishiking](https://github.com/tanishiking))
- fix: scala-steward cron
  [\#4336](https://github.com/scalameta/metals/pull/4336)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Fix wrong condition for logging in CodeActionBuilder
  [\#4334](https://github.com/scalameta/metals/pull/4334)
  ([tgodzik](https://github.com/tgodzik))
- [skip ci] bugfix: Fix Scala Steward cron
  [\#4335](https://github.com/scalameta/metals/pull/4335)
  ([tgodzik](https://github.com/tgodzik))
- fix ConvertToNamedArg for constructor invocations
  [\#4313](https://github.com/scalameta/metals/pull/4313)
  ([jkciesluk](https://github.com/jkciesluk))
- feature: Use PC for documentHighlight
  [\#4307](https://github.com/scalameta/metals/pull/4307)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Always set changes or document changes
  [\#4332](https://github.com/scalameta/metals/pull/4332)
  ([tgodzik](https://github.com/tgodzik))
- refactor: update scala-steward configs (run more frequently and limit number
  of PRs) [\#4329](https://github.com/scalameta/metals/pull/4329)
  ([tanishiking](https://github.com/tanishiking))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.2 to 3.10.3
  [\#4320](https://github.com/scalameta/metals/pull/4320)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump coursier/setup-action from 1.2.0 to 1.2.1
  [\#4326](https://github.com/scalameta/metals/pull/4326)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- workflows: specify java version
  [\#4327](https://github.com/scalameta/metals/pull/4327)
  ([dos65](https://github.com/dos65))
- build(deps): Update mill-contrib-testng from 0.10.5 to 0.10.7
  [\#4319](https://github.com/scalameta/metals/pull/4319)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.1.3 to 9.1.6
  [\#4321](https://github.com/scalameta/metals/pull/4321)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jsoup from 1.15.2 to 1.15.3
  [\#4322](https://github.com/scalameta/metals/pull/4322)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-scalac, ... from 4.5.11 to 4.5.13
  [\#4323](https://github.com/scalameta/metals/pull/4323)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.11 to 0.1.12
  [\#4324](https://github.com/scalameta/metals/pull/4324)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ammonite-util from 2.5.4-13-1ebd00a6 to 2.5.4-19-cd76521f
  [\#4318](https://github.com/scalameta/metals/pull/4318)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update metaconfig-core from 0.11.0 to 0.11.1
  [\#4317](https://github.com/scalameta/metals/pull/4317)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Add support for Scala 3.2.0
  [\#4315](https://github.com/scalameta/metals/pull/4315)
  ([tgodzik](https://github.com/tgodzik))
- fix extract value for `new` keyword
  [\#4314](https://github.com/scalameta/metals/pull/4314)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Fix nightlies after recent changes
  [\#4312](https://github.com/scalameta/metals/pull/4312)
  ([tgodzik](https://github.com/tgodzik))
- fix: check_scala3_nightly - switch from bash on Scala
  [\#4311](https://github.com/scalameta/metals/pull/4311)
  ([dos65](https://github.com/dos65))
- Issue#4154: Add builder for lsp code actions
  [\#4302](https://github.com/scalameta/metals/pull/4302)
  ([PeuTit](https://github.com/PeuTit))
- update TestGroups [\#4310](https://github.com/scalameta/metals/pull/4310)
  ([dos65](https://github.com/dos65))
- chore: mention Scala CLi in new file provider
  [\#4306](https://github.com/scalameta/metals/pull/4306)
  ([dos65](https://github.com/dos65))
- fix: don't break using-directives by an auto-import for `.sc` in scala-cli
  [\#4291](https://github.com/scalameta/metals/pull/4291)
  ([dos65](https://github.com/dos65))
- fix: Fix docs copy paste error.
  [\#4305](https://github.com/scalameta/metals/pull/4305)
  ([antosha417](https://github.com/antosha417))
- chore: mark buildTarget/javacOptions as unsupported for ammonite
  [\#4303](https://github.com/scalameta/metals/pull/4303)
  ([dos65](https://github.com/dos65))
- refactor: Do not index extension methods in workspaceSymbolIndex
  [\#4300](https://github.com/scalameta/metals/pull/4300)
  ([tanishiking](https://github.com/tanishiking))
- chore: reuse code actions kinds for all code actions
  [\#4299](https://github.com/scalameta/metals/pull/4299)
  ([kpodsiad](https://github.com/kpodsiad))
- add isStale filter in IndexedContext
  [\#4298](https://github.com/scalameta/metals/pull/4298)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Find correct definition of apply when using case classes
  [\#4296](https://github.com/scalameta/metals/pull/4296)
  ([tgodzik](https://github.com/tgodzik))
- feat: better mill-bsp semanticdb support
  [\#4295](https://github.com/scalameta/metals/pull/4295)
  ([ckipp01](https://github.com/ckipp01))
- feature: Automatically add extension methods in string interpolation
  [\#4292](https://github.com/scalameta/metals/pull/4292)
  ([tgodzik](https://github.com/tgodzik))
- chore: improve scalatest style inferring
  [\#4293](https://github.com/scalameta/metals/pull/4293)
  ([kpodsiad](https://github.com/kpodsiad))
- feat: implement basic Text Explorer support for scalatest
  [\#4281](https://github.com/scalameta/metals/pull/4281)
  ([kpodsiad](https://github.com/kpodsiad))
- bugfix: Allow to run/debug in ScalaCli when started from Metals
  [\#4289](https://github.com/scalameta/metals/pull/4289)
  ([tgodzik](https://github.com/tgodzik))
- chore: Adjust ScalaCLI version detection to new changes
  [\#4288](https://github.com/scalameta/metals/pull/4288)
  ([tgodzik](https://github.com/tgodzik))
- fix: fix implement-all members for anonymous class
  [\#4284](https://github.com/scalameta/metals/pull/4284)
  ([tanishiking](https://github.com/tanishiking))
- Bump LSP4j to 0.15.0 that adds notebook support
  [\#4282](https://github.com/scalameta/metals/pull/4282)
  ([tanishiking](https://github.com/tanishiking))
- ci: fix check_scala3_nightly workflow
  [\#4280](https://github.com/scalameta/metals/pull/4280)
  ([dos65](https://github.com/dos65))
- Don't run InteractiveDriver.run if the content didn't "change"
  [\#4225](https://github.com/scalameta/metals/pull/4225)
  ([tanishiking](https://github.com/tanishiking))
- add Scala 3.2.0-RC4 [\#4277](https://github.com/scalameta/metals/pull/4277)
  ([dos65](https://github.com/dos65))
- Fix mtags release [\#4279](https://github.com/scalameta/metals/pull/4279)
  ([dos65](https://github.com/dos65))
- Better title for `extract value` code action
  [\#4274](https://github.com/scalameta/metals/pull/4274)
  ([jkciesluk](https://github.com/jkciesluk))
- upgrade bloop to 1.5.3-28-373a64c9
  [\#4275](https://github.com/scalameta/metals/pull/4275)
  ([dos65](https://github.com/dos65))
- Add autofill named arguments completion for scala3
  [\#4248](https://github.com/scalameta/metals/pull/4248)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: [Scala 3] Auto import symbols in string interpolation
  [\#4273](https://github.com/scalameta/metals/pull/4273)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Properly restart worksheet presentation compiler
  [\#4270](https://github.com/scalameta/metals/pull/4270)
  ([tgodzik](https://github.com/tgodzik))
- fix: release 8 for mtag-interfaces
  [\#4269](https://github.com/scalameta/metals/pull/4269)
  ([dos65](https://github.com/dos65))
- fix: specify `--release 8` flags
  [\#4267](https://github.com/scalameta/metals/pull/4267)
  ([dos65](https://github.com/dos65))
- build(deps): Update mdoc-interfaces from 2.3.2 to 2.3.3
  [\#4264](https://github.com/scalameta/metals/pull/4264)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.9 to 0.1.11
  [\#4266](https://github.com/scalameta/metals/pull/4266)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update org.eclipse.lsp4j, ... from 0.14.0 to 0.15.0
  [\#4262](https://github.com/scalameta/metals/pull/4262)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.0.4 to 9.1.3
  [\#4263](https://github.com/scalameta/metals/pull/4263)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update undertow-core from 2.2.18.Final to 2.2.19.Final
  [\#4261](https://github.com/scalameta/metals/pull/4261)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update metaconfig-core from 0.10.0 to 0.11.0
  [\#4259](https://github.com/scalameta/metals/pull/4259)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.1 to 3.10.2
  [\#4260](https://github.com/scalameta/metals/pull/4260)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Don't show preparing PC message
  [\#4257](https://github.com/scalameta/metals/pull/4257)
  ([tgodzik](https://github.com/tgodzik))
- refactor: include the companion object name in code action title
  [\#4258](https://github.com/scalameta/metals/pull/4258)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Check if the target can be compiled
  [\#4256](https://github.com/scalameta/metals/pull/4256)
  ([tgodzik](https://github.com/tgodzik))
- Revert "[Scala 3] Revert type completions feature (#4236)"
  [\#4237](https://github.com/scalameta/metals/pull/4237)
  ([tanishiking](https://github.com/tanishiking))
- Add better Scala CLI support
  [\#3790](https://github.com/scalameta/metals/pull/3790)
  ([alexarchambault](https://github.com/alexarchambault))
- bugfix: Fix signature help when named params are present
  [\#4251](https://github.com/scalameta/metals/pull/4251)
  ([tgodzik](https://github.com/tgodzik))
- Fix ArgCompletions for nested apply
  [\#4247](https://github.com/scalameta/metals/pull/4247)
  ([jkciesluk](https://github.com/jkciesluk))
- adding AmmoniteFileCompletions for scala 3
  [\#4220](https://github.com/scalameta/metals/pull/4220)
  ([vzmerr](https://github.com/vzmerr))
- bugfix: Don't throw exception if template ends at EOF
  [\#4245](https://github.com/scalameta/metals/pull/4245)
  ([tgodzik](https://github.com/tgodzik))
- feature: Adjust type if mismatch
  [\#4218](https://github.com/scalameta/metals/pull/4218)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Remove worksheet diagnostics on close
  [\#4233](https://github.com/scalameta/metals/pull/4233)
  ([tgodzik](https://github.com/tgodzik))
- chore(ci): migrate to actions/java to use built-in sbt caching
  [\#4221](https://github.com/scalameta/metals/pull/4221)
  ([ckipp01](https://github.com/ckipp01))
- feat: include anon classes (val x = new Trait {}) in 'find all
  implementations' [\#4231](https://github.com/scalameta/metals/pull/4231)
  ([kpodsiad](https://github.com/kpodsiad))
- docs: fix release note metadata
  [\#4241](https://github.com/scalameta/metals/pull/4241)
  ([tanishiking](https://github.com/tanishiking))
- release: Add release notes for Metals 0.11.8
  [\#4214](https://github.com/scalameta/metals/pull/4214)
  ([tanishiking](https://github.com/tanishiking))
