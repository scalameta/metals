---
author: Vadim Chelyshov
title: Metals v0.11.4 - Aluminium
authorURL: https://twitter.com/_dos65
authorImageURL: https://github.com/dos65.png
---

We're happy to announce the release of Metals v0.11.4, which includes the hotfix of [the issue with `cs install metals`](https://github.com/coursier/coursier/issues/2406).

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">3</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">3</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">2</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">1</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">0</td>
  </tr>
</tbody>
</table>

Metals is a language server for Scala that works with VS Code, Vim, Emacs and
Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from [Lunatech](https://lunatech.com) along with contributors from
the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Fix the issue with installing Metals using coursier

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.3..v0.11.4
2	Kamil Podsiadło
1	Vadim Chelyshov
```

## Merged PRs

## [v0.11.4](https://github.com/scalameta/metals/tree/v0.11.4) (2022-04-27)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.3...v0.11.4)

**Merged pull requests:**

- upgrade bsp4j version to a stable one
  [\#3872](https://github.com/scalameta/metals/pull/3872)
  ([dos65](https://github.com/dos65))
- chore: update server version after release
  [\#3869](https://github.com/scalameta/metals/pull/3869)
  ([kpodsiad](https://github.com/kpodsiad))
- docs: release notes
  [\#3860](https://github.com/scalameta/metals/pull/3860)
  ([kpodsiad](https://github.com/kpodsiad))
