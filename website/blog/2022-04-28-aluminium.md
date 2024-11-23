---
authors: dos65
title: Metals v0.11.5 - Aluminium
---

We're happy to announce the release of Metals v0.11.5, which brings a few of
hotfixes and improves hover info for Scala 3

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">28</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">7</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">5</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">2</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center"></td>
  </tr>
</tbody>
</table>

For full details: https://github.com/scalameta/metals/milestone/50?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the [Scala Center](https://scala.epfl.ch/)
and [VirtusLab](https://virtuslab.com) with the help from
[Lunatech](https://lunatech.com) along with contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Fix javasemanticdb support for sbt-BSP with JDK17
- Don't show MUnit warning for the Test Explorer when not necessary
- [Scala3] Show correct tooltip on hover methods in for comprehension

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.4..v0.11.5
16	Rikito Taniguchi
4	Vadim Chelyshov
1	Kamil Podsiadlo
1	Tomasz Godzik
1	ckipp01
```

## Merged PRs

## [v0.11.5](https://github.com/scalameta/metals/tree/v0.11.5) (2022-04-28)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.4...v0.11.5)

**Merged pull requests:**

- fix: don't show MUnit warning for the Test Explorer when not necessary
  [\#3879](https://github.com/scalameta/metals/pull/3879)
  ([kpodsiad](https://github.com/kpodsiad))
- fix: sbt-metals + java semanticdb issue on jdk17
  [\#3876](https://github.com/scalameta/metals/pull/3876)
  ([dos65](https://github.com/dos65))
- [Scala3] Show correct tooltip on hover methods in for comprehension
  [\#3854](https://github.com/scalameta/metals/pull/3854)
  ([tanishiking](https://github.com/tanishiking))
- docs: update vim docs to no longer point people to coc-metals
  [\#3819](https://github.com/scalameta/metals/pull/3819)
  ([ckipp01](https://github.com/ckipp01))
- Remove Eclipse from the documentation
  [\#3868](https://github.com/scalameta/metals/pull/3868)
  ([tgodzik](https://github.com/tgodzik))
- update versions for 0.11.4
  [\#3874](https://github.com/scalameta/metals/pull/3874)
  ([dos65](https://github.com/dos65))
- 0.11.4 release notes [\#3873](https://github.com/scalameta/metals/pull/3873)
  ([dos65](https://github.com/dos65))
