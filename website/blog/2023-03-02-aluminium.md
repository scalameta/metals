---
authors: tgodzik
title: Metals v0.11.11 - Aluminium
---

We're happy to announce the release of Metals v0.11.11, which brings in a couple
of new features and improvements as well as stability fixes. Further releases
and possibly 1.0.0 release will focus on providing a better stable development
environment.

<table>
<tbody>
    <tr>
    <td>Commits since last release</td>
    <td align="center">177</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">138</td>
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
    <td align="center">7</td>
  </tr>
</tbody>
</table>

For full details: [https://github.com/scalameta/metals/milestone/55?closed=1]
(https://github.com/scalameta/metals/milestone/55?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Added support for Scala 3.2.2](#support-for-scala-322).
- [Introduce Support for Semantic Tokens](#introduce-support-for-semantic-tokens)
- [New Inline value code action](#new-inline-value-code-action)
- [Expanded workspace symbol search to include fields](#expanded-workspace-symbol-search-to-include-fields)
- [Allow users to use older Scala versions](#allow-users-to-use-older-scala-versions)
- [Improve match-case completions in Scala 2](#improve-match-case-completions-in-scala-2)
- [Fallback to symbol search for code navigation](#fallback-to-symbol-search-for-code-navigation)
- [Automatically add details to github issue](#automatically-add-details-to-github-issue)

## Support for Scala 3.2.2

Release notes from
[https://www.scala-lang.org/news/3.2.2](https://www.scala-lang.org/news/3.2.2)

This version not only fixes bugs but also brings two new flags:

- `-Vrepl-max-print-characters` allows you to configure how many characters can
  be printed in the REPL before truncating the output. The default limit was
  also raised from 1,000 to 50,000 characters.

- `-Ylightweight-lazy-vals` enables new lazy vals implementation. It can be much
  more performant, especially in cases of parallel access. However, it can cause
  problems when used inside of GraalVM native image. We will make the new
  implementation the default one as soon as we fix those problems.

## Introduce Support for Semantic Tokens

Semantic tokens is a newer addition to the language server protocol that enables
editors to provide syntax highlighting based on the knowledge provided by the
language server. Each symbol within the Scala file should now have a more
meaningful color dependent on whether it's a class, field, operator, etc.

This feature is now available in Metals thanks to the main work done by
[ShintaroSasaki](https://github.com/ShintaroSasaki) during last year's Google
summer of code and some later additional effort by other Metals contributors
mainly [jkciesluk](https://github.com/jkciesluk). Since this feature would
impact all the users in every file they open, we decided to put it first behind
an additional user setting `metals.enableSemanticHighlighting`, which needs to
be set to true. Later depending on your editor, you might also need to enable it
in some other places.

For example in VS Code you also need to set
`editor.semanticHighlighting.enabled` to `true`. So the settings json files
would look like this:

```json
...
  "metals.enableSemanticHighlighting": true,
  "editor.semanticHighlighting.enabled": true,
...
```

You can also modify your theme colors and styles for some particular types of
symbols. For example:

```json
"editor.semanticTokenColorCustomizations": {
  "[Adapta Nokto]": {
    "rules": {
      "variable": "#c95252",
      "*.readonly": "#8d91b8",
      "*.deprecated": {
        "strikethrough": true
      }
    }
  }
},
```

will make vars red and values light blue while also crossing out any deprecated
methods or classes.

![semantic-tokens](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-03-02-aluminium/vkllczg.png?raw=true)

For setting up semantic tokens in editors other than VS Code please consult the
relevant documentation.

## New inline value code action

Inline value is a new code action contributed by
[kasiaMarek](https://github.com/kasiaMarek) that allows users to inline the
right hand side of any value if the definition of that value is located within
the same file. This works in two ways:

- if inlining at a reference we will only replace the current value reference

![replace-one](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-03-02-aluminium/yLuM079.gif?raw=true)

- if inlining at the definition we will try to replace all the references and
  remove the definition if the definition cannot be accessed from outside the
  current file

![replace-all](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-03-02-aluminium/LdlSQsB.gif?raw=true)

## Expanded workspace symbol search to include fields

Previously, when searching the workspace Metals would only look for classes,
trait, interfaces, enums and objects. If you wanted to search for a particularly
named method you would need to use normal text search, which would be much less
convenient. This release adds the ability to also search for method, types or
values within the workspace. This will not work for dependencies in order to not
increase indexes used by Metals too much.

![search](https://github.com/scalameta/gh-pages-images/blob/master/metals/2023-03-02-aluminium/YQCinNz.gif?raw=true)

Another great contribution from [kasiaMarek](https://github.com/kasiaMarek)!

## Allow users to use older Scala versions

To provide support for a particular Scala version Metals needs to release a
separate version for each of them, which causes a strain on CI infrastructure.
Because of that previously we would stop supporting some older Scala versions.
However, now instead of entirely dropping support for older versions, we will
only freeze the amount of features and not fix any new bugs for any of those
older versions.

We will still recommend updating to the newest possible binary compatible
version of the Scala version you are using, but in case it's not possible for
you it should be safe to use Metals with those older versions.

In this release we removed Scala 3.0.0, 2.13.1, 2.13.2, 2.12.9, 3.0.0 and 3.0.1.
You can still use them, but some of the newest features will not be available
for them including inline and semantic tokens.

## Improve match-case completions in Scala 2

Last release introduced some improvements for completions in pattern matching in
Scala 3. This release brings them also to Scala 2 including:

- pattern only completions, i.e. completions in:

```scala
Option(1) match {
  case No@@
  case _: So@@
```

- completions like:

```scala
case class Foo(a: Int, b: Int)
List(Foo(1,2)).map{ case F@@ }
// turns into
List(Foo(1,2)).map{ case Foo(a,b) => }
```

- exhaustive match completions in anonymous functions:

```scala
List(Option(1)).map { case@@ }
// turns into
List(Option(1)).map {
  case Some(value) =>
  case None =>
}
```

In all the examples `@@` denotes the position of the cursor.

This cool improvement was added by [jkciesluk](https://github.com/jkciesluk).

## Fallback to symbol search for code navigation

Metals uses both global semanticdb indexes as well as the compiler itself to
find the definition of any symbol. However, whenever your workspace had problems
with compilation both those methods could have failed to actually find that
definition. Instead of giving up, since Metals indexes all the names of types,
methods etc. we will try to find the symbol by name within the user's workspace.

This heuristic might sometimes offer false positives, but it's much more useful
than being left with no definition location at all. We will continue to improve
those heuristics to make sure that the number of false positives is reduced.

## Automatically add details to github issue

Thanks to [kasiaMarek](https://github.com/kasiaMarek) whenever users open an
issue from the Metals tree view or via the `open-new-github-issue` command, the
issue will have all the details filled out automatically. This should reduce the
amount of information that the users need to type manually and make it much more
efficient to submit new issues.

## Miscellaneous

- bugfix: Correctly sort dependency completions in case of non standard
  versions. [jkciesluk](https://github.com/jkciesluk)
- bugfix: Fix issues with spaces in workspace path on Windows.
- improvement: Allow rename in lambdas even if code doesn't compile.
- bugfix: Fix running main when spaces are involved on Windows.
- improvement: Allow extracting member when file contains toplevel definition.
  [jkciesluk](https://github.com/jkciesluk)
- bugfix [Scala 3]: Don't show named argument completions on infix methods.
- improvement[Scala 3]: add `*` completion when at `import foo.`.
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Automatically add repositories to init script for gradle
  ([kasiaMarek](https://github.com/kasiaMarek)).
- fix: Correctly insert type annotation in tuples lambda args
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Use classpath jar for defining run classpath.
- improvement: Allow manual cancel of starting debug session.
- bugfix: ensure a new `.metals/` dir isn't create when/where undesired.
- bugfix: Fix issues with jars containing + on windows.
- bugfix: Always check if a file with scala/java extension is not a directory.
- bugfix: Fix issues with go to definition in derived clauses.
- bugfix[Scala 3]: Correctly highlight enum case in lists.
- bugfix: Don't show completions in patterns when writing `new`
  [kasiaMarek](https://github.com/kasiaMarek).
- bugfix: Don't highlight generated given if it doesn't exist.
- bugfix: Do not convert to named arguments for a Java class.
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Implement all members for case classes in Scala 3
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Insert imports in the correct place in ScalaCLI scripts
- bugfix: Also accept interfaces for go to implementation
- bugfix: Support Scala 3.0.x worksheets
- bugfix: Support scala command as BSP server for debug
- improvement: add completion for scala cli `//> using dep@@`
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Fix issues with auto imports not showing up on windows
- bugfix: Correctly highlight last name when using trailing comma
  [jkciesluk](https://github.com/jkciesluk)
- feature: Suggest `derives` after `extend` or instead of it.
  [jkciesluk](https://github.com/jkciesluk)
- bugfix: Don't change packages in files if they move outside workspace
- feature: Add the possibility to discover the main class command to run
- improvement: Add better exhaustive completions for &, | types
  [jkciesluk](https://github.com/jkciesluk)
- improvement: Automatically add details to github issue
  [kasiaMarek](https://github.com/kasiaMarek)
- bugfix: Always check if a Scala file is an actual file
- bugfix: Remove additional indent for auto imports in worksheets
  [jkciesluk](https://github.com/jkciesluk)
- bugfix [Scala 3]: Fix infinite loop in case of complex inline expressions
- bugfix: Fix JDK version when Java home is jre folder
  [adpi2](https://github.com/adpi2)
- bugfix [Scala 3]: Suggest class constructors when importing symbols
- bugfix: Fix infinite loop when searching for munit test cases
- bugfix: inner methods passed as params for extract method code action
  [kasiaMarek](https://github.com/kasiaMarek)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.10..v0.11.11
  Tomasz Godzik
  Jakub Ciesluk
  Katarzyna Marek
  Chris Kipp
  Kamil Podsiadło
  Tobias Roeser
  Joao Azevedo
  Maciej Gajek
  Shintaro Sasaki
  Adrien Piquerez
  Jędrzej Rochala
```

## Merged PRs

## [v0.11.11](https://github.com/scalameta/metals/tree/v0.11.11) (2023-03-02)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.10...v0.11.11)

**Merged pull requests:**

- dep: bump gradleBloop from 1.5.8.to 1.6.0
  [\#5020](https://github.com/scalameta/metals/pull/5020)
  ([ckipp01](https://github.com/ckipp01))
- build(deps): bump @docusaurus/core from 2.3.0 to 2.3.1 in /website
  [\#5018](https://github.com/scalameta/metals/pull/5018)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @docusaurus/plugin-client-redirects from 2.3.0 to 2.3.1 in
  /website [\#5017](https://github.com/scalameta/metals/pull/5017)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps): bump @docusaurus/preset-classic from 2.3.0 to 2.3.1 in /website
  [\#5016](https://github.com/scalameta/metals/pull/5016)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- improvement: Add test for type completion out of scope
  [\#5015](https://github.com/scalameta/metals/pull/5015)
  ([jkciesluk](https://github.com/jkciesluk))
- refactor: Move SemanticTokens to metals package
  [\#5002](https://github.com/scalameta/metals/pull/5002)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Cancel and create new server in test retry
  [\#5011](https://github.com/scalameta/metals/pull/5011)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: inner methods passed as params for extract method code action
  [\#5004](https://github.com/scalameta/metals/pull/5004)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix infinite loop when searching for munit test cases
  [\#5008](https://github.com/scalameta/metals/pull/5008)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Suggest class constructors when importing symbols
  [\#5000](https://github.com/scalameta/metals/pull/5000)
  ([tgodzik](https://github.com/tgodzik))
- [sbt-metals] Fix JDK version when Java home is jre folder
  [\#4910](https://github.com/scalameta/metals/pull/4910)
  ([adpi2](https://github.com/adpi2))
- bugfix: Don't collect symbols from Inline expansion
  [\#4999](https://github.com/scalameta/metals/pull/4999)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 3.3.0-RC3
  [\#4998](https://github.com/scalameta/metals/pull/4998)
  ([tgodzik](https://github.com/tgodzik))
- fix: Remove additional indent for imports in worksheets
  [\#4927](https://github.com/scalameta/metals/pull/4927)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: remove leftover from PR #4954
  [\#4996](https://github.com/scalameta/metals/pull/4996)
  ([rochala](https://github.com/rochala))
- bugfix: Always check if a Scala file is an actual file
  [\#4997](https://github.com/scalameta/metals/pull/4997)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.12.0 to 9.15.0
  [\#4994](https://github.com/scalameta/metals/pull/4994)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update org.eclipse.lsp4j, ... from 0.19.0 to 0.20.0
  [\#4993](https://github.com/scalameta/metals/pull/4993)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update coursier from 2.1.0-RC4 to 2.1.0-RC6
  [\#4992](https://github.com/scalameta/metals/pull/4992)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.7 to 3.11.0
  [\#4990](https://github.com/scalameta/metals/pull/4990)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update semanticdb-java from 0.8.9 to 0.8.13
  [\#4991](https://github.com/scalameta/metals/pull/4991)
  ([scalameta-bot](https://github.com/scalameta-bot))
- feature: Add Semantic Tokens support in Scala 3
  [\#4946](https://github.com/scalameta/metals/pull/4946)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update sbt-welcome from 0.2.2 to 0.3.1
  [\#4980](https://github.com/scalameta/metals/pull/4980)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bsp4j from 2.1.0-M3 to 2.1.0-M4
  [\#4979](https://github.com/scalameta/metals/pull/4979)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mill-contrib-testng from 0.10.10 to 0.10.11
  [\#4982](https://github.com/scalameta/metals/pull/4982)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ammonite-util from 2.5.6 to 2.5.8
  [\#4981](https://github.com/scalameta/metals/pull/4981)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.6 to 3.10.7
  [\#4983](https://github.com/scalameta/metals/pull/4983)
  ([scalameta-bot](https://github.com/scalameta-bot))
- fix: Wrong semantic token for renamed imported classes
  [\#4976](https://github.com/scalameta/metals/pull/4976)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: Don't use cache when querying for Scala nightlies
  [\#4972](https://github.com/scalameta/metals/pull/4972)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Merge input and input3 projects used for tests
  [\#4959](https://github.com/scalameta/metals/pull/4959)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add better exhaustive completions for &, | types
  [\#4965](https://github.com/scalameta/metals/pull/4965)
  ([jkciesluk](https://github.com/jkciesluk))
- Improvement: adds details to github issue
  [\#4966](https://github.com/scalameta/metals/pull/4966)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feature: Add the possibility to discover the main class to run
  [\#4968](https://github.com/scalameta/metals/pull/4968)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Don't change packages in files if they move outside workspace
  [\#4967](https://github.com/scalameta/metals/pull/4967)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: completions for keyword lookalikes
  [\#4922](https://github.com/scalameta/metals/pull/4922)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feat: Add `derives` keyword completion
  [\#4953](https://github.com/scalameta/metals/pull/4953)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Set terminated to true when timeout
  [\#4964](https://github.com/scalameta/metals/pull/4964)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Remove scalameta parsing in InferredTypeProvider
  [\#4954](https://github.com/scalameta/metals/pull/4954)
  ([tgodzik](https://github.com/tgodzik))
- fix: Adjust position in PcCollector for trailing comma
  [\#4952](https://github.com/scalameta/metals/pull/4952)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Fix issues with auto imports not showing up
  [\#4956](https://github.com/scalameta/metals/pull/4956)
  ([tgodzik](https://github.com/tgodzik))
- improvement: add completion for scala cli //> using dep@@
  [\#4963](https://github.com/scalameta/metals/pull/4963)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Support scala command as BSP server for debug
  [\#4962](https://github.com/scalameta/metals/pull/4962)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Don't fail on no shutdown message
  [\#4960](https://github.com/scalameta/metals/pull/4960)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix completion tests and make them more stable
  [\#4957](https://github.com/scalameta/metals/pull/4957)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add classpath to expect semantic tokens suite
  [\#4958](https://github.com/scalameta/metals/pull/4958)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Catch StackOverflowException from the parser
  [\#4902](https://github.com/scalameta/metals/pull/4902)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add null check in SemanticTokenProvider
  [\#4951](https://github.com/scalameta/metals/pull/4951)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Support Scala 3.0.x worksheets
  [\#4949](https://github.com/scalameta/metals/pull/4949)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Also accept interfaces for go to implementation
  [\#4948](https://github.com/scalameta/metals/pull/4948)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Remove check for stacktrace markers
  [\#4947](https://github.com/scalameta/metals/pull/4947)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Allow users to use older Scala support
  [\#4935](https://github.com/scalameta/metals/pull/4935)
  ([tgodzik](https://github.com/tgodzik))
- chore: Fix expected line in FindTextInDependencyJarsSuite
  [\#4942](https://github.com/scalameta/metals/pull/4942)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Insert imports in the correct place in ScalaCLI scripts
  [\#4940](https://github.com/scalameta/metals/pull/4940)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Print information if compiler failed to infer edits for import
  [\#4941](https://github.com/scalameta/metals/pull/4941)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Also allow nonboostrapped to fall back to the last nightly
  [\#4938](https://github.com/scalameta/metals/pull/4938)
  ([tgodzik](https://github.com/tgodzik))
- docs[skip ci]: Don't suggest updating mima
  [\#4937](https://github.com/scalameta/metals/pull/4937)
  ([tgodzik](https://github.com/tgodzik))
- fix: when inlining lambda value add brackets
  [\#4890](https://github.com/scalameta/metals/pull/4890)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: Implement all members for case classes in Scala 3
  [\#4923](https://github.com/scalameta/metals/pull/4923)
  ([jkciesluk](https://github.com/jkciesluk))
- fix: do not convert to named arguments for a Java class
  [\#4870](https://github.com/scalameta/metals/pull/4870)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Don't highlight generated given if it doesn't exist
  [\#4916](https://github.com/scalameta/metals/pull/4916)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add the possibility to only support running the code
  [\#4919](https://github.com/scalameta/metals/pull/4919)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Correctly highlight enum case in lists
  [\#4917](https://github.com/scalameta/metals/pull/4917)
  ([tgodzik](https://github.com/tgodzik))
- fix: do not show run lenses for other platforms than JVM
  [\#4918](https://github.com/scalameta/metals/pull/4918)
  ([kpodsiad](https://github.com/kpodsiad))
- bugfix: Fix `select.nameSpan` for expressions in parentheses
  [\#4907](https://github.com/scalameta/metals/pull/4907)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Update test for Windows
  [\#4913](https://github.com/scalameta/metals/pull/4913)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: case pattern completions for bind
  [\#4912](https://github.com/scalameta/metals/pull/4912)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix issues with go to definition in derived clauses
  [\#4909](https://github.com/scalameta/metals/pull/4909)
  ([tgodzik](https://github.com/tgodzik))
- feature: workspace symbol search global var/val/def/type
  [\#4798](https://github.com/scalameta/metals/pull/4798)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Always check if a scala/java file is actually a file
  [\#4891](https://github.com/scalameta/metals/pull/4891)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add support for Scala 3.3.0-RC2
  [\#4908](https://github.com/scalameta/metals/pull/4908)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Fix issues with jars containing + on windows
  [\#4899](https://github.com/scalameta/metals/pull/4899)
  ([tgodzik](https://github.com/tgodzik))
- refactor: make scala3RC easier to work with
  [\#4900](https://github.com/scalameta/metals/pull/4900)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Add checks if span exists
  [\#4901](https://github.com/scalameta/metals/pull/4901)
  ([tgodzik](https://github.com/tgodzik))
- fix: ensure a new `.metals/` dir isn't create when/where undesired
  [\#4895](https://github.com/scalameta/metals/pull/4895)
  ([ckipp01](https://github.com/ckipp01))
- chore: add support for 3.3.0-RC1
  [\#4897](https://github.com/scalameta/metals/pull/4897)
  ([ckipp01](https://github.com/ckipp01))
- fix(ci): don't run scripted tests twice
  [\#4898](https://github.com/scalameta/metals/pull/4898)
  ([ckipp01](https://github.com/ckipp01))
- Update Mill documentation
  [\#4887](https://github.com/scalameta/metals/pull/4887)
  ([lefou](https://github.com/lefou))
- improvement: Allow manual cancel of starting debug session
  [\#4873](https://github.com/scalameta/metals/pull/4873)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update cli_3, scala-cli-bsp from 0.1.19 to 0.1.20
  [\#4878](https://github.com/scalameta/metals/pull/4878)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore[Scala 3]: Add back unused warnings and remove all unused
  [\#4880](https://github.com/scalameta/metals/pull/4880)
  ([tgodzik](https://github.com/tgodzik))
- Revert scalafmt update [\#4882](https://github.com/scalameta/metals/pull/4882)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Use classpath jar for defining run classpath
  [\#4868](https://github.com/scalameta/metals/pull/4868)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update scalafmt-core, scalafmt-dynamic from 3.6.1 to 3.7.0
  [\#4876](https://github.com/scalameta/metals/pull/4876)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 9.11.0 to 9.12.0
  [\#4875](https://github.com/scalameta/metals/pull/4875)
  ([scalameta-bot](https://github.com/scalameta-bot))
- feature: inline values [\#4759](https://github.com/scalameta/metals/pull/4759)
  ([kasiaMarek](https://github.com/kasiaMarek))
- fix: correctly insert type annotation in tuples lambda args
  [\#4859](https://github.com/scalameta/metals/pull/4859)
  ([jkciesluk](https://github.com/jkciesluk))
- bugfix: Fix compilation with newest nightlies
  [\#4866](https://github.com/scalameta/metals/pull/4866)
  ([tgodzik](https://github.com/tgodzik))
- chore: update sbt-launcher.jar to 1.8.2
  [\#4865](https://github.com/scalameta/metals/pull/4865)
  ([ckipp01](https://github.com/ckipp01))
- improvement: Fallback to symbol search as the last possibility for definition
  [\#4861](https://github.com/scalameta/metals/pull/4861)
  ([tgodzik](https://github.com/tgodzik))
- fix: init script repositories for gradle
  [\#4773](https://github.com/scalameta/metals/pull/4773)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Better exhaustive-case completion
  [\#4831](https://github.com/scalameta/metals/pull/4831)
  ([jkciesluk](https://github.com/jkciesluk))
- Fix: add star to import completions in Scala 3
  [\#4855](https://github.com/scalameta/metals/pull/4855)
  ([kasiaMarek](https://github.com/kasiaMarek))
- refactor: Split DebugAdapterStart command into separate commands
  [\#4860](https://github.com/scalameta/metals/pull/4860)
  ([tgodzik](https://github.com/tgodzik))
- feature: Add semantic tokens for sbt and script files
  [\#4853](https://github.com/scalameta/metals/pull/4853)
  ([tgodzik](https://github.com/tgodzik))
- bugfix [Scala 3]: Don't show named argument completions on infix methods
  [\#4857](https://github.com/scalameta/metals/pull/4857)
  ([tgodzik](https://github.com/tgodzik))
- fix: In bloop directories were seen as scala/java files
  [\#4856](https://github.com/scalameta/metals/pull/4856)
  ([jkciesluk](https://github.com/jkciesluk))
- refactor: adding wrapper for metals server configuration
  [\#4845](https://github.com/scalameta/metals/pull/4845)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Allow extracting member when file contains toplevel
  `val`|`def`|`given` etc.
  [\#4803](https://github.com/scalameta/metals/pull/4803)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update interface from 1.0.12 to 1.0.13
  [\#4854](https://github.com/scalameta/metals/pull/4854)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Check if symbol exists before invoking .owner
  [\#4838](https://github.com/scalameta/metals/pull/4838)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Make sure that correct extension param is highlighted
  [\#4852](https://github.com/scalameta/metals/pull/4852)
  ([tgodzik](https://github.com/tgodzik))
- feature: Add support for Scala 3.2.2
  [\#4850](https://github.com/scalameta/metals/pull/4850)
  ([tgodzik](https://github.com/tgodzik))
- fix: ensure doctor reports that diagnostics in sbt targets work
  [\#4842](https://github.com/scalameta/metals/pull/4842)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Properly check symbol in classOf
  [\#4846](https://github.com/scalameta/metals/pull/4846)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Improve performance of `SemanticTokens`
  [\#4835](https://github.com/scalameta/metals/pull/4835)
  ([jkciesluk](https://github.com/jkciesluk))
- improvement: Always log issues with BSP requests
  [\#4843](https://github.com/scalameta/metals/pull/4843)
  ([tgodzik](https://github.com/tgodzik))
- fix: ensure when we go from quickpick to showMessageRequest we include type
  [\#4841](https://github.com/scalameta/metals/pull/4841)
  ([ckipp01](https://github.com/ckipp01))
- chore: Update Ammonite to 2.5.6
  [\#4840](https://github.com/scalameta/metals/pull/4840)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Disable -Wunused:all for Scala 3
  [\#4836](https://github.com/scalameta/metals/pull/4836)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Add benchmarks for SemanticHighlight
  [\#4833](https://github.com/scalameta/metals/pull/4833)
  ([tgodzik](https://github.com/tgodzik))
- fix: properly shut down Metals server
  [\#4829](https://github.com/scalameta/metals/pull/4829)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: fix name shadowing in pc collector
  [\#4818](https://github.com/scalameta/metals/pull/4818)
  ([jkciesluk](https://github.com/jkciesluk))
- build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.5 to 3.10.6
  [\#4830](https://github.com/scalameta/metals/pull/4830)
  ([scalameta-bot](https://github.com/scalameta-bot))
- docs: update `architecture.md` after #4776 (split MetalsLanguageServer` into
  separate two parts) [\#4826](https://github.com/scalameta/metals/pull/4826)
  ([kpodsiad](https://github.com/kpodsiad))
- bugfix: Only request build targets from a connection
  [\#4825](https://github.com/scalameta/metals/pull/4825)
  ([tgodzik](https://github.com/tgodzik))
- refactor: split `MetalsLanguageServer` into language server and language
  service [\#4776](https://github.com/scalameta/metals/pull/4776)
  ([kpodsiad](https://github.com/kpodsiad))
- bugfix: Fix running when spaces are involved on windows
  [\#4815](https://github.com/scalameta/metals/pull/4815)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): Update flyway-core from 9.10.2 to 9.11.0
  [\#4819](https://github.com/scalameta/metals/pull/4819)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-dynamic from 3.5.9 to 3.6.1
  [\#4822](https://github.com/scalameta/metals/pull/4822)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update ipcsocket from 1.6.1 to 1.6.2
  [\#4821](https://github.com/scalameta/metals/pull/4821)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt, scripted-plugin from 1.8.0 to 1.8.2
  [\#4820](https://github.com/scalameta/metals/pull/4820)
  ([scalameta-bot](https://github.com/scalameta-bot))
- refactor: improve reporting on Semanticdb from Mill for Java Modules
  [\#4816](https://github.com/scalameta/metals/pull/4816)
  ([ckipp01](https://github.com/ckipp01))
- chore: ensure semanticdb-java gets updated by Scala
  [\#4817](https://github.com/scalameta/metals/pull/4817)
  ([ckipp01](https://github.com/ckipp01))
- Semantic Highlighting [\#4444](https://github.com/scalameta/metals/pull/4444)
  ([ShintaroSasaki](https://github.com/ShintaroSasaki))
- refactor: Move `jvmRunEnvironment` to after indexing
  [\#4777](https://github.com/scalameta/metals/pull/4777)
  ([jkciesluk](https://github.com/jkciesluk))
- Update semanticdb-java version to 0.8.9
  [\#4812](https://github.com/scalameta/metals/pull/4812)
  ([lefou](https://github.com/lefou))
- improvement: Allow compiler rename in lambdas
  [\#4808](https://github.com/scalameta/metals/pull/4808)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): bump react from 17.0.2 to 18.2.0 in /website
  [\#4811](https://github.com/scalameta/metals/pull/4811)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- chore(ci): allow dependabot to update website stuff
  [\#4809](https://github.com/scalameta/metals/pull/4809)
  ([ckipp01](https://github.com/ckipp01))
- bugfix: Fix issues with spaces in workspace path
  [\#4807](https://github.com/scalameta/metals/pull/4807)
  ([tgodzik](https://github.com/tgodzik))
- docs: add a note about supported test frameworks
  [\#4805](https://github.com/scalameta/metals/pull/4805)
  ([ckipp01](https://github.com/ckipp01))
- chore(site): bump docusaurus to 2.2.0
  [\#4806](https://github.com/scalameta/metals/pull/4806)
  ([ckipp01](https://github.com/ckipp01))
- fix: correctly sort dependency completions
  [\#4804](https://github.com/scalameta/metals/pull/4804)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Deprecate and remove more Scala versions
  [\#4720](https://github.com/scalameta/metals/pull/4720)
  ([tgodzik](https://github.com/tgodzik))
- fix(website): Make sure Markdown link is rendered correctly in Metals v0.11.10
  announcement [\#4800](https://github.com/scalameta/metals/pull/4800)
  ([jcazevedo](https://github.com/jcazevedo))
- feat: Improve match-case completions in Scala 2
  [\#4744](https://github.com/scalameta/metals/pull/4744)
  ([jkciesluk](https://github.com/jkciesluk))
- chore: Update Metals and Scala versions
  [\#4797](https://github.com/scalameta/metals/pull/4797)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add release notes for Metals 0.11.10
  [\#4740](https://github.com/scalameta/metals/pull/4740)
  ([tgodzik](https://github.com/tgodzik))
