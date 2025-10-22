---
authors: tgodzik
title: Metals v0.11.7 - Aluminium
---

We're happy to announce the release of Metals v0.11.7, which brings a large
number of Scala 3 improvements as well as a few interesting code actions.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">164</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">77</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">14</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">14</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">9</td>
  </tr>
</tbody>
</table>

For full details: https://github.com/scalameta/metals/milestone/51?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Release notes now shown in VS Code
- Improvements to Bloop Java home automation.
- Allow to run all scalafix rules on a file
- [Scala 3] Implement-all abstract members code action.
- [Scala 3] Add snippet completions.
- [Scala 3] Add file name completions.
- [Scala 3] Expression evaluation for debugger.
- [Scala 2] Add ConvertToNamedArguments code action.
- New flatMap to for comprehension code action.

## Release notes now shown in VS Code

If you are using Visual Studio Code you should see this message right after the
update of the Metals extension has finished. 🚀

Starting with the current release we will be showing release notes inside VS
Code when the version update completes. This way users will know whenever the
extension was updated and it will not just silently update in the background.

Release notes will be shown only once after the update, but can be viewed again
by executing `Metals: Show release notes`

This was contributed by [kpodsiad](https://github.com/kpodsiad), thanks for the
continuous contributions!

## Improvements to Bloop Java home automation

From the
[last version of Metals](https://scalameta.org/metals/blog/2022/06/03/aluminium#automatically-setup-java-home-for-the-bloop-build-server)
we introduced an automated setup of Java home for your Bloop build server, which
is used to compile your code under the hood and is the default build server to
be used.

Your Java Home would be added when starting Metals, before starting the build
server, and would also suggest an update when changing the versions in the
Metals settings. This could result in two possible notifications:

- Metals changing Java version:

![metals-java-home](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/BH8AS5c.png?raw=true)

- Metals detecting an existing Bloop Java configuration:

![metals-java-home-existing](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/PCyyNVT.png?raw=true)

However, this caused a couple of issues:

1. In some cases Metals would suggest to change your Java home even if it hasn't
   changed and it wasn't possible to dismiss the notification forever.
2. In case your Java home changed and you missed the notification (or the
   notification was not shown), Bloop would not start if the old java home was
   removed. That especially caused issues with usage of Java with Nix.
3. Metals would suggest to update your ~/.bloop/bloop.json file even if it was
   readonly.

To help with that we implemented a number of fixes:

1. You can dismiss the notification forever if Metals mistakenly suggests
   updating your Bloop configuration and the overall false positives have been
   reduced. To do that you just need to click on the
   `Use the Global File's JVM...` button.
2. Metals will check before starting if your Java home is correct, and if it's
   not it will replace it with the current one used by Metals.
3. Metals will not show any notifications if it cannot change the file itself.

If you encounter any further issues, you can take a look at Metals output and it
should show logs like these:

```
2022.07.01 16:14:41 INFO  Bloop uses /usr/lib/jvm/openjdk17 defined at /home/tgodzik/.bloop/bloop.json
2022.07.01 16:14:41 INFO  Bloop currently uses settings: -Xmx3G
```

You can use them to debug what is happening and report an issue.

## Allow to run all scalafix rules on a file

Up until the current release Metals would only run scalafix for
`organize imports` and wouldn't allow to use any other rules. Starting with this
release you should be able to apply all currently defined
`rules in .scalafix.conf` you require by running `metals.scalafix-run` command.

By default it's bound to `alt` + `ctrl` + `shift` + `o` shortcut in Visual
Studio Code.

![scalafix-run](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/DLe5sYT.gif?raw=true)

One major drawback of the current approach is that we are unable to run some of
the custom rules. Only the default rules and the contributed Hygiene rules are
currently available by default. You can see the full list of them
[here](https://scalacenter.github.io/scalafix/docs/rules/community-rules.html#hygiene-rules).
For all the rest users will have to use a previously added setting
`metals.scalafixRulesDependencies`, where you can add dependencies following the
[coursier](https://get-coursier.io/) convention such as
`com.github.liancheng::organize-imports:0.6.0`.

Let us know if there is another rule we should add to the default ones!

The current situation could be improved by making sure that the dependencies for
scalafix are listed in the configuration itself. This would allow both Metals
and scalafix CLI to easily run rules on your workspace. You can find the
discussion on
[scalafix github page](https://github.com/scalacenter/scalafix/issues/1625)

## [Scala 3] Implement-all abstract members code action.

Implement-all abstract members code action is finally available in Scala 3 🎉

Previously, in Scala3, when you were implementing an abstract member in a
concrete class, you could only see the completion to override particular
members. However, you needed to add those members one by one. Now you'll see a
code action to implement all the members at once.

![implement-all](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/foU3oHL.gif?raw=true)

Great work by [tanishiking](https://github.com/tanishiking).

## [Scala 3] Add snippet completions.

Previously, whenever users accepted method completions the only inserted text
would be the name of the method. With snippets we can now add additional `()`
with cursor ending in or after the parenthesis depending on whether the method
accepts parameters.

![snippets](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/QEbPSTd.gif?raw=true)

For editors that do not support snippets the previous behaviour is preserved.

## [Scala 3] Add file name completions

A file name completion feature has been available only in the Scala 2 project,
but thanks to the effort by [riiswa](https://github.com/riiswa), Metals 0.11.7
will complete class name based on the enclosing file, in Scala3!

![filename-completion](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/f10pnJ6.gif?raw=true)

## [Scala 3] Expression evaluation for debugger.

Expression evaluation was already available for use in your Scala 2 code,
however it was still missing for Scala 3. Thanks to the main work by
[tdudzik](https://github.com/tdudzik) and later finishing touches and multiple
bug fixes by [adpi2](https://github.com/adpi2) it is now possible to to evaluate
your code on breakpoints in the Scala 3 code.

![debug](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/jYs7QdM.gif?raw=true)

It was added in the previous 0.11.6 version of Metals, however it was still not
working correctly enough to promote it. Currently, it should be working
correctly in most cases, so please do test it out and report any issues you find
to [scala-debug-adapter](https://github.com/scalacenter/scala-debug-adapter).

## [Scala 2] Add ConvertToNamedArguments code action

Metals 0.11.7 allows users to use a new code action that can convert all
arguments to named arguments, which is only available on Scala 2 for the time
being.

The new code action will work on method calls with multiple arguments, where\
it's sometimes hard to tell which arguments match which parameters at a glance.
In this situation you may want to make them named arguments. Now you will be
able to automatically convert all those arguments into named arguments.

![convert-to-named-vscode](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/SiUYUSY.gif?raw=true)

We'd like to thank [@camgraff](https://github.com/camgraff) for
[implementing this feature](https://github.com/scalameta/metals/pull/3971) 🥳

## New flatMap to for comprehension code action.

When working with long chains of map/flatMap/filter it might sometimes be useful
to replace them with for comprehensions, which can give you a flatter view of
your code.

This is purely a syntactic sugar which gives us an ability to offer a code
action to automatically convert any chain of the mentioned methods with a for
comprehensions.

![for-comp](https://github.com/scalameta/gh-pages-images/blob/master/metals/2022-07-04-aluminium/MOZLYi0.gif?raw=true)

There is currently one known issue with two separate chains being inside one
another, which might cause unexpected issues. Please report any other issues you
find!

Amazing effort from [zmerr](https://github.com/zmerr)

## Miscellaneous

- Updated Bloop version to 1.5.2, which should allow for a better
  `clean compile workspace` behaviour.
- Always print out CFR output if there is any available even if the command
  printed to the error output.
- Add correct parenthesis when using extension methods completions.
  [riiswa](https://github.com/riiswa)
- Properly show links to implicit parameters in decoration hovers inside for
  comprehensions.
- Run doctor when `more information` button was clicked
- Fix issues with Metals support for Scala 3.1.x hanging due to infinite loop in
  the compiler.

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.6..v0.11.7
27	Tomasz Godzik
    22	Cam Graff
    18	Rikito Taniguchi
    15	Scalameta Bot
    10	ckipp01
     5	Waris Radji
     5	scalameta-bot
     4	Vadim Chelyshov
     3	Adrien Piquerez
     2	Kamil Podsiadlo
     2	tgodzik
     1	Chris Kipp
     1	Kamil Podsiadło
     1	zmerr
```

## Merged PRs

## [v0.11.7](https://github.com/scalameta/metals/tree/v0.11.7) (2022-07-04)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.6...v0.11.7)

**Merged pull requests:**

- Fix sbt-dependengy-graph workflow again
  [\#4095](https://github.com/scalameta/metals/pull/4095)
  ([adpi2](https://github.com/adpi2))
- Fix sbt-dependency-graph workflow
  [\#4092](https://github.com/scalameta/metals/pull/4092)
  ([adpi2](https://github.com/adpi2))
- fix: ConvertToNamedArgument code action: register the server command / hide in
  Scala3 projects [\#4090](https://github.com/scalameta/metals/pull/4090)
  ([tanishiking](https://github.com/tanishiking))
- fix: (scala3) insert correct position for implement-all code action on class
  with type parameters [\#4081](https://github.com/scalameta/metals/pull/4081)
  ([tanishiking](https://github.com/tanishiking))
- chore: Bump Bloop to newest version
  [\#4077](https://github.com/scalameta/metals/pull/4078)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Change Java home on startup if broken
  [\#4077](https://github.com/scalameta/metals/pull/4079)
  ([tgodzik](https://github.com/tgodzik))
- feature: Add known scalafix rules' dependencies
  [\#4077](https://github.com/scalameta/metals/pull/4077)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: [Scala 3] Adjust changes for the most recent compiler version
  [\#4071](https://github.com/scalameta/metals/pull/4071)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Don't cache Java for nix and additionally use JAVA_HOME
  [\#4075](https://github.com/scalameta/metals/pull/4075)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Make sure that the Scala 3 compiler always retries properly
  [\#4074](https://github.com/scalameta/metals/pull/4074)
  ([tgodzik](https://github.com/tgodzik))
- chore: Uncomment HKSignatureHelpSuite
  [\#4072](https://github.com/scalameta/metals/pull/4072)
  ([tgodzik](https://github.com/tgodzik))
- chore: handle memory footprint errors on JDK17
  [\#4064](https://github.com/scalameta/metals/pull/4064)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: lsif-java -> scip-java
  [\#3972](https://github.com/scalameta/metals/pull/3972)
  ([ckipp01](https://github.com/ckipp01))
- feature: Don't update readonly bloop.json and allow to dismiss notifications
  [\#4049](https://github.com/scalameta/metals/pull/4049)
  ([tgodzik](https://github.com/tgodzik))
- feat: OverrideCompletion can handle symbolPrefix
  [\#4037](https://github.com/scalameta/metals/pull/4037)
  ([tanishiking](https://github.com/tanishiking))
- docs: Add Rikito Taniguchi to maintainers
  [\#4058](https://github.com/scalameta/metals/pull/4058)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Fix wrong type shown when type alias is used
  [\#4046](https://github.com/scalameta/metals/pull/4046)
  ([tgodzik](https://github.com/tgodzik))
- docs: Update release documentation
  [\#4051](https://github.com/scalameta/metals/pull/4051)
  ([tanishiking](https://github.com/tanishiking))
- fix: support tab indent in implement-all code action
  [\#4042](https://github.com/scalameta/metals/pull/4042)
  ([tanishiking](https://github.com/tanishiking))
- flatMap to For Comprehension
  [\#3885](https://github.com/scalameta/metals/pull/3885)
  ([zmerr](https://github.com/zmerr))
- bugfix: Fix wrong flag for default methods
  [\#4045](https://github.com/scalameta/metals/pull/4045)
  ([tgodzik](https://github.com/tgodzik))
- fix: remove improper javaOptions initialization
  [\#4047](https://github.com/scalameta/metals/pull/4047)
  ([dos65](https://github.com/dos65))
- fix: do not fail when stripping ansi codes
  [\#4048](https://github.com/scalameta/metals/pull/4048)
  ([kpodsiad](https://github.com/kpodsiad))
- Add ConvertToNamedArguments code action
  [\#3971](https://github.com/scalameta/metals/pull/3971)
  ([camgraff](https://github.com/camgraff))
- add scala 3.1.3 and 3.2.0-RC1
  [\#4041](https://github.com/scalameta/metals/pull/4041)
  ([dos65](https://github.com/dos65))
- Adjust to signature help changes in scala3 compiler
  [\#4026](https://github.com/scalameta/metals/pull/4026)
  ([dos65](https://github.com/dos65))
- feat(mill): also check default version in mill file
  [\#4039](https://github.com/scalameta/metals/pull/4039)
  ([ckipp01](https://github.com/ckipp01))
- fix(worksheets): filter out NonUnitStatements wartremover in worksheets
  [\#4038](https://github.com/scalameta/metals/pull/4038)
  ([ckipp01](https://github.com/ckipp01))
- build(deps): Update munit from 1.0.0-M3 to 1.0.0-M5
  [\#4033](https://github.com/scalameta/metals/pull/4033)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.8.3 to 3.9.0
  [\#4031](https://github.com/scalameta/metals/pull/4031)
  ([scalameta-bot](https://github.com/scalameta-bot))
- [Scala 3] Add file name completions
  [\#4018](https://github.com/scalameta/metals/pull/4018)
  ([riiswa](https://github.com/riiswa))
- build(deps): Update undertow-core from 2.2.17.Final to 2.2.18.Final
  [\#4032](https://github.com/scalameta/metals/pull/4032)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update requests from 0.7.0 to 0.7.1
  [\#4030](https://github.com/scalameta/metals/pull/4030)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update h2 from 2.1.212 to 2.1.214
  [\#4029](https://github.com/scalameta/metals/pull/4029)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-scalafix, scalafix-interfaces from 0.10.0 to 0.10.1
  [\#4028](https://github.com/scalameta/metals/pull/4028)
  ([scalameta-bot](https://github.com/scalameta-bot))
- feat: Implement-all abstract members code action for Scala3
  [\#3960](https://github.com/scalameta/metals/pull/3960)
  ([tanishiking](https://github.com/tanishiking))
- bugfix: Properly send back experimental capabilities
  [\#4007](https://github.com/scalameta/metals/pull/4007)
  ([tgodzik](https://github.com/tgodzik))
- chore(maven): update the maven wrapper
  [\#4024](https://github.com/scalameta/metals/pull/4024)
  ([ckipp01](https://github.com/ckipp01))
- fix: run doctor when `more information` button was clicked
  [\#4019](https://github.com/scalameta/metals/pull/4019)
  ([kpodsiad](https://github.com/kpodsiad))
- chore: Fix errors for kind projector
  [\#4015](https://github.com/scalameta/metals/pull/4015)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Reset diagnostics before connecting to new server
  [\#4017](https://github.com/scalameta/metals/pull/4017)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Properly show links to implicit parameters in decoration hovers
  [\#4014](https://github.com/scalameta/metals/pull/4014)
  ([tgodzik](https://github.com/tgodzik))
- feature: Allow to run all scalafix rules on a file
  [\#3996](https://github.com/scalameta/metals/pull/3996)
  ([tgodzik](https://github.com/tgodzik))
- Fix extension methods completion
  [\#4013](https://github.com/scalameta/metals/pull/4013)
  ([riiswa](https://github.com/riiswa))
- chore: Add support for Scala 2.12.16
  [\#4010](https://github.com/scalameta/metals/pull/4010)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Always print out CFR output if there is any available
  [\#4012](https://github.com/scalameta/metals/pull/4012)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 3.1.3-RC5
  [\#4011](https://github.com/scalameta/metals/pull/4011)
  ([tgodzik](https://github.com/tgodzik))
- Fix auto implement abstract members for self-types
  [\#4009](https://github.com/scalameta/metals/pull/4009)
  ([riiswa](https://github.com/riiswa))
- fix: only compare Bloop JavaHome with Metals JavaHome if set
  [\#4002](https://github.com/scalameta/metals/pull/4002)
  ([ckipp01](https://github.com/ckipp01))
- fix: add checkout to steward job
  [\#3999](https://github.com/scalameta/metals/pull/3999)
  ([ckipp01](https://github.com/ckipp01))
- chore(deps): add in other scalameta repos to steward job
  [\#3995](https://github.com/scalameta/metals/pull/3995)
  ([ckipp01](https://github.com/ckipp01))
- fix: switch mtags release on a branch with backports
  [\#3998](https://github.com/scalameta/metals/pull/3998)
  ([dos65](https://github.com/dos65))
- bugfix: Fix failing tests after refactor
  [\#3993](https://github.com/scalameta/metals/pull/3993)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update ammonite-util from 2.5.3 to 2.5.4
  [\#3983](https://github.com/scalameta/metals/pull/3983)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalameta, semanticdb-scalac, ... from 4.5.6 to 4.5.9
  [\#3991](https://github.com/scalameta/metals/pull/3991)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Update lsp4j to 0.14.0
  [\#3958](https://github.com/scalameta/metals/pull/3958)
  ([tanishiking](https://github.com/tanishiking))
- build(deps): Update munit from 1.0.0-M3 to 1.0.0-M4
  [\#3990](https://github.com/scalameta/metals/pull/3990)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jsoup from 1.14.3 to 1.15.1
  [\#3989](https://github.com/scalameta/metals/pull/3989)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 8.5.10 to 8.5.12
  [\#3988](https://github.com/scalameta/metals/pull/3988)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update interface from 1.0.6 to 1.0.7
  [\#3986](https://github.com/scalameta/metals/pull/3986)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier from 2.1.0-M5 to 2.1.0-M6
  [\#3985](https://github.com/scalameta/metals/pull/3985)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.8.2 to 3.8.3
  [\#3984](https://github.com/scalameta/metals/pull/3984)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-config, bloop-launcher from 1.5.0-18-003e6c7b to
  1.5.0-22-91111c15 [\#3982](https://github.com/scalameta/metals/pull/3982)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Update tests to work with newest nightlies
  [\#3981](https://github.com/scalameta/metals/pull/3981)
  ([tgodzik](https://github.com/tgodzik))
- refactor: Fix testing library breakpoints and simplify metals adapter logic
  [\#3966](https://github.com/scalameta/metals/pull/3966)
  ([tgodzik](https://github.com/tgodzik))
- fix: correct steward cron
  [\#3980](https://github.com/scalameta/metals/pull/3980)
  ([ckipp01](https://github.com/ckipp01))
- chore(ci): add Scala Steward into CI
  [\#3977](https://github.com/scalameta/metals/pull/3977)
  ([ckipp01](https://github.com/ckipp01))
- feature: [Scala 3] Add snippet completions
  [\#3959](https://github.com/scalameta/metals/pull/3959)
  ([tgodzik](https://github.com/tgodzik))
- doc: fix release note v0.11.6 (duplicated merged commits section)
  [\#3979](https://github.com/scalameta/metals/pull/3979)
  ([tanishiking](https://github.com/tanishiking))
- chore: update version for v0.11.6
  [\#3978](https://github.com/scalameta/metals/pull/3978)
  ([tanishiking](https://github.com/tanishiking))
- docs: release note for Metals 0.11.6
  [\#3957](https://github.com/scalameta/metals/pull/3957)
  ([tanishiking](https://github.com/tanishiking))
