---
authors: tanishiking
title: Metals v0.11.6 - Aluminium
---

We're happy to announce the release of Metals v0.11.6 which continues to improve
the Scala 3 support along with many other fixes.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">165</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">56</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">12</td>
  </tr>
</tbody>
</table>

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

- reduce memory usage in large projects
- override completions for Scala3
- improved scaladoc support in both Scala2 and Scala3
- better UX in the test explorer
- automatically setup java home for the Bloop build server
- support for Scala 3.1.3-RC3 and RC4

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

## Reduce file watcher memory usage

Previously, Metals consumed a huge amount of memory for file watchers in large
projects, especially on macOS. Now, Metals uses a memory-efficient way to watch
files to detect changes and consume less memory.

For more technical details, see the original PR:
[\#3758](https://github.com/scalameta/metals/pull/3758).

## [Scala3] Override Completions

Override completions for Scala3 are now available with Metals 0.11.6! Now,
Metals shows the scaladoc on hover for Scala3 projects. (Before this release,
Metals was unable to show the scaladoc for the symbols from third-party
modules).

![override-completion](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-06-03-aluminium/Go3sMxy.gif?raw=true)

## [Scala3] Show scaladoc on hover for Scala 3 project

Previously, scaladocs were missing for a lot of classes and methods in Scala 3,
especially for the symbols from third-party modules. From this release, Metals
will always show the scaladoc on hover for Scala3 projects.

![hover-scala3](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-06-03-aluminium/Svzq5DD.png?raw=true)

### [Scala3] Support `completionItem/resolve`

`completionItem/resolve` is a feature that provides on-demand, more detailed
information when moving through the list of suggested completions. It will show
documentation, proper parameter names for Java methods, and default values for
Scala 3 methods. Now, this is also available for use in Scala 3.

![completion-item-resolve](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-06-03-aluminium/Tz6AOsx.gif?raw=true)

## Show parent scaladoc if implementation is returning empty

Scaladocs can be inspected whenever you hover, use a completion or signature
help. Up until recently we only showed you the documentation if the exact method
you are using had the scaladocs written, which meant that if you overrode a
method and didn't add the scaladoc comments again we would not show you any
documentation. One example of such method is `headOption` on `List`.

From this release we will also search the parent method in case the current
method's scaladoc are empty.

## [Scala 3] Show scaladocs for signature help

As mentioned in the previous paragraph, Metals can show you documentation in
three different places. That, however, was true only for Scala 2 previously. In
this release, we will now show you proper documentation whenever invoking
signature help.

As a reminder, signature help is used to indicate what parameters can be used in
a method. It should pop up automatically after writing `(`, but you can also
invoke it manually. In VS Code that takes the form of
`editor.action.triggerParameterHints` command, which can also be bound to a
shortcut and by default is.

## [MUnit] Test Explorer can find helper methods from parent classes

MUnit allows to use
[helper functions](https://scalameta.org/munit/docs/tests.html#declare-tests-inside-a-helper-function)
when declaring tests. Very often those helper methods are extracted to some
parent classes which are extended by many test suites. Now, Metals can find
usages of those helper methods and display them in Test Explorer.

This feature is available for Bloop and SBT 1.7.0-M2 or later.

![MUnit-helper-methods](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-06-03-aluminium/GGRDpXA.gif?raw=true)

## Support Cats Effect stacktraces in stacktrace analyzer

Cats Effect offers
[asynchronous stack tracing](https://typelevel.org/cats-effect/docs/tracing#asynchronous-stack-tracing)
which augments exceptions with additional information.\
Now, Stacktrace Analyzer is able to recognize CE's stacktraces and provide link
to location in code. Say no to tedious debugging when you have only stacktrace
from the logs!

![cats-effect-stacktraces](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-06-03-aluminium/5fMvcYd.gif?raw=true)

## Improve implement all completion and code action

Previously, when we invoked the "implement all members" completion and code
action, Metals used `x$0` for Java parameter names. Now, Metals fills these with
the correct parameter names.

## Improve rewrite to braces/parenthesis code action

Sometimes, it wasn't clear which code would be affected by
[rewrite to braces/parenthesis](https://scalameta.org/metals/blog/2021/09/06/tungsten#replace--with--in-functions-and-vice-versa)
code action. Now, code action's description contains name of the function/method
which will be affected by executing action.
![rewrite-braces-parens](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-06-03-aluminium/SkHolsJ.gif?raw=true) Theme:
[One Dark Pro](https://marketplace.visualstudio.com/items?itemName=zhuangtongfa.Material-theme)

## Automatically setup java home for the Bloop build server

In previous versions of Metals, if users wanted to change the java version of
the Bloop build server, they would need to manually update `.bloop/bloop.json`
file in their user home directory. With this version, we ensure that the Bloop
Java version will correspond to Metals one to avoid weird compilation issues
that could arise from different versions being used.

Now, each time you update `javaHome` or `bloopJvmProperties` settings, Metals
will ask you whether to forward those changes to the Bloop configuration file.
If you modified that file previously or want to use a custom one, you can
dismiss the Metals notification. Otherwise, everything will automatically be set
up for you once you decide to apply the changes. If you never created the file,
we will create it before starting Bloop, so you should not notice anything out
of order.

## Support for Scala 3.1.3-RC4, 3.1.3-RC3

Metals 0.11.6 supports Scala 3.1.3-RC3 and RC4.

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.5..v0.11.6
    34	Rikito Taniguchi
    18	Tomasz Godzik
    14	Scala Steward
    11	Vadim Chelyshov
    10	zmerr
     7	Kamil Podsiadlo
     5	Pavol Vidlička
     5	ckipp01
     4	Arman Bilge
     2	Ian Tabolt
     1	Arthur McGibbon
     1	tgodzik
```

## Merged PRs

## [v0.11.6](https://github.com/scalameta/metals/tree/v0.11.6) (2022-06-03)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.5...v0.11.6)

**Merged pull requests:**

- bugfix: Escape java home path on windows
  [\#3969](https://github.com/scalameta/metals/pull/3969)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Remove Wconf from scalac options in worksheets
  [\#3976](https://github.com/scalameta/metals/pull/3976)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Show all synthetics inside for comprehensions
  [\#3974](https://github.com/scalameta/metals/pull/3974)
  ([tgodzik](https://github.com/tgodzik))
- fix: revert temporal changes in completions tests for 3.2.*-NIGHTLY
  [\#3970](https://github.com/scalameta/metals/pull/3970)
  ([dos65](https://github.com/dos65))
- docs: remove status-bar as option for slowTaskProvider
  [\#3967](https://github.com/scalameta/metals/pull/3967)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Fallback to sourcepath if jar cannot be found
  [\#3962](https://github.com/scalameta/metals/pull/3962)
  ([tgodzik](https://github.com/tgodzik))
- fix: prevent npe in filewatcher
  [\#3964](https://github.com/scalameta/metals/pull/3964)
  ([dos65](https://github.com/dos65))
- feat: add function name to rewrite parens/braces code action
  [\#3965](https://github.com/scalameta/metals/pull/3965)
  ([kpodsiad](https://github.com/kpodsiad))
- (scala3) Override completions
  [\#3897](https://github.com/scalameta/metals/pull/3897)
  ([tanishiking](https://github.com/tanishiking))
- actions: return release job lock
  [\#3944](https://github.com/scalameta/metals/pull/3944)
  ([dos65](https://github.com/dos65))
- Fix SBT version check in Doctor
  [\#3946](https://github.com/scalameta/metals/pull/3946)
  ([iantabolt](https://github.com/iantabolt))
- Fix issues with synthetics in for comprehension's yield
  [\#3948](https://github.com/scalameta/metals/pull/3948)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 3.1.3-RC4
  [\#3947](https://github.com/scalameta/metals/pull/3947)
  ([tgodzik](https://github.com/tgodzik))
- Update Bloop Java Home to that of Metals
  [\#3871](https://github.com/scalameta/metals/pull/3871)
  ([zmerr](https://github.com/zmerr))
- chore: speedup lsp tests
  [\#3925](https://github.com/scalameta/metals/pull/3925)
  ([dos65](https://github.com/dos65))
- feature: [Scala 3] Show correct inferred type in signature help
  [\#3941](https://github.com/scalameta/metals/pull/3941)
  ([tgodzik](https://github.com/tgodzik))
- docs(test-explorer): add note about test-user-interface
  [\#3943](https://github.com/scalameta/metals/pull/3943)
  ([ckipp01](https://github.com/ckipp01))
- fix: avoid out of bounds for editors that treat \n as a line end
  [\#3942](https://github.com/scalameta/metals/pull/3942)
  ([ckipp01](https://github.com/ckipp01))
- feature: Show scaladocs for signature help
  [\#3934](https://github.com/scalameta/metals/pull/3934)
  ([tgodzik](https://github.com/tgodzik))
- update `metals_ref` for mtags-release
  [\#3940](https://github.com/scalameta/metals/pull/3940)
  ([dos65](https://github.com/dos65))
- Fix nightlies tests and uncomment SignaturePat suite tests
  [\#3932](https://github.com/scalameta/metals/pull/3932)
  ([tgodzik](https://github.com/tgodzik))
- Include proper names when using implement all completion and code action
  [\#3930](https://github.com/scalameta/metals/pull/3930)
  ([tgodzik](https://github.com/tgodzik))
- Fix expression type for inlined methods and show all flags for methods
  [\#3931](https://github.com/scalameta/metals/pull/3931)
  ([tgodzik](https://github.com/tgodzik))
- feature: [Scala 3] Add completion item resolution
  [\#3914](https://github.com/scalameta/metals/pull/3914)
  ([tgodzik](https://github.com/tgodzik))
- chore(docs): ensure metals_2.12 is replaced with metals_2.13
  [\#3924](https://github.com/scalameta/metals/pull/3924)
  ([ckipp01](https://github.com/ckipp01))
- fix: symbolSearch - support encoded names from classpath
  [\#3917](https://github.com/scalameta/metals/pull/3917)
  ([dos65](https://github.com/dos65))
- Update scalafmt-dynamic to 3.5.3
  [\#3921](https://github.com/scalameta/metals/pull/3921)
  ([scala-steward](https://github.com/scala-steward))
- Update mill-contrib-testng to 0.10.4
  [\#3920](https://github.com/scalameta/metals/pull/3920)
  ([scala-steward](https://github.com/scala-steward))
- Update scalameta, semanticdb-scalac, ... to 4.5.6
  [\#3922](https://github.com/scalameta/metals/pull/3922)
  ([scala-steward](https://github.com/scala-steward))
- Update scalafmt-core to 3.5.3
  [\#3916](https://github.com/scalameta/metals/pull/3916)
  ([scala-steward](https://github.com/scala-steward))
- chore: update scala3 version in welcome msg
  [\#3919](https://github.com/scalameta/metals/pull/3919)
  ([kpodsiad](https://github.com/kpodsiad))
- chore, docs: simplify cats-effect stacktrace regex, add docstring
  [\#3918](https://github.com/scalameta/metals/pull/3918)
  ([kpodsiad](https://github.com/kpodsiad))
- refactor: fix warning from Scala3 in ScaladocParser.scala
  [\#3912](https://github.com/scalameta/metals/pull/3912)
  ([tanishiking](https://github.com/tanishiking))
- Add support for Scala 3.1.3-RC3
  [\#3911](https://github.com/scalameta/metals/pull/3911)
  ([tgodzik](https://github.com/tgodzik))
- Update Bloop and Scala Debug adapter
  [\#3910](https://github.com/scalameta/metals/pull/3910)
  ([tgodzik](https://github.com/tgodzik))
- fix: scala3 - do not provide completions for invalid quals
  [\#3909](https://github.com/scalameta/metals/pull/3909)
  ([dos65](https://github.com/dos65))
- feat, test explorer: search for test methods in parent classes
  [\#3898](https://github.com/scalameta/metals/pull/3898)
  ([kpodsiad](https://github.com/kpodsiad))
- Add semanticdb-javac to `TestInternal`
  [\#3907](https://github.com/scalameta/metals/pull/3907)
  ([armanbilge](https://github.com/armanbilge))
- Update munit to newest milestone
  [\#3906](https://github.com/scalameta/metals/pull/3906)
  ([tgodzik](https://github.com/tgodzik))
- docs: fix broken documents (Integrating a new editor)
  [\#3905](https://github.com/scalameta/metals/pull/3905)
  ([tanishiking](https://github.com/tanishiking))
- fix: broken link display in vim docs
  [\#3904](https://github.com/scalameta/metals/pull/3904)
  ([ckipp01](https://github.com/ckipp01))
- feat: handle cats-effect async stacktrace in stacktrace analyzer
  [\#3900](https://github.com/scalameta/metals/pull/3900)
  ([kpodsiad](https://github.com/kpodsiad))
- Don't create the Presentation Compiler for Java files
  [\#3887](https://github.com/scalameta/metals/pull/3887)
  ([tgodzik](https://github.com/tgodzik))
- Handle modules in stacktrace analyzer
  [\#3896](https://github.com/scalameta/metals/pull/3896)
  ([Arthurm1](https://github.com/Arthurm1))
- Update scalafmt-dynamic to 3.5.2
  [\#3893](https://github.com/scalameta/metals/pull/3893)
  ([scala-steward](https://github.com/scala-steward))
- Update ujson to 2.0.0 [\#3890](https://github.com/scalameta/metals/pull/3890)
  ([scala-steward](https://github.com/scala-steward))
- Update xnio-nio to 3.8.7.Final
  [\#3892](https://github.com/scalameta/metals/pull/3892)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.5.10
  [\#3891](https://github.com/scalameta/metals/pull/3891)
  ([scala-steward](https://github.com/scala-steward))
- Update ammonite-util to 2.5.3
  [\#3889](https://github.com/scalameta/metals/pull/3889)
  ([scala-steward](https://github.com/scala-steward))
- Reduce file watcher memory usage
  [\#3758](https://github.com/scalameta/metals/pull/3758)
  ([pvid](https://github.com/pvid))
- Update scalameta, semanticdb-scalac, ... to 4.5.5
  [\#3894](https://github.com/scalameta/metals/pull/3894)
  ([scala-steward](https://github.com/scala-steward))
- docs: fix latest versions
  [\#3886](https://github.com/scalameta/metals/pull/3886)
  ([dos65](https://github.com/dos65))
- Show parent scaladoc if implementation is returning empty
  [\#3881](https://github.com/scalameta/metals/pull/3881)
  ([tgodzik](https://github.com/tgodzik))
- Add scaladocs on hover for Scala 3
  [\#3865](https://github.com/scalameta/metals/pull/3865)
  ([tanishiking](https://github.com/tanishiking))
- 0.11.5 - update versions
  [\#3883](https://github.com/scalameta/metals/pull/3883)
  ([dos65](https://github.com/dos65))
- 0.11.5 release notes [\#3882](https://github.com/scalameta/metals/pull/3882)
  ([dos65](https://github.com/dos65))
