---
authors: kmarek
title: Metals v1.5.1 - Strontium
---

We're happy to announce the release of Metals v1.5.1, which brings a hotfix for go to definition error reporting.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">12</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">9</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">6</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center"></td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center"></td>
  </tr>
</tbody>
</table>

For full details: [https://github.com/scalameta/metals/milestone/76?closed=1](https://github.com/scalameta/metals/milestone/76?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- [Create an error report only on an empty definition](#create-an-error-report-only-on-empty-definition)

## Create an error report only on an empty definition
Fix for a bug, where an error report would be created always on go to definition action.

## Miscellaneous
- improvement: Don't fail on creating short type (Scala 2) [kasiaMarek](https://github.com/kasiaMarek)
- improvement: Default to empty object if no metals section [tgodzik](https://github.com/tgodzik)
- improvement: Use only current source tree for searching for local symbols in pc (Scala 3) [kasiaMarek](https://github.com/kasiaMarek)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.5.0..v1.5.1
     5	kasiaMarek
     2	Scalameta Bot
     3	Tomasz Godzik
     1	Katarzyna Marek
     1	dependabot[bot]
     1	tgodzik
```

## Merged PRs

## [v1.5.1](https://github.com/scalameta/metals/tree/v1.5.1) (2025-01-24)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.5.0...v1.5.1)

**Merged pull requests:**

- fix: report error only on an empty definition
  [\#7155](https://github.com/scalameta/metals/pull/7155)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update cli_3, scala-cli-bsp from 1.5.4 to 1.6.1
  [\#7154](https://github.com/scalameta/metals/pull/7154)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scalafmt-core from 3.8.5 to 3.8.6
  [\#7152](https://github.com/scalameta/metals/pull/7152)
  ([scalameta-bot](https://github.com/scalameta-bot))
- bugfix: Start http server earlier
  [\#7084](https://github.com/scalameta/metals/pull/7084)
  ([tgodzik](https://github.com/tgodzik))
- improvement: don't fail on `shortType`
  [\#7148](https://github.com/scalameta/metals/pull/7148)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: Default to empty object if no metals section
  [\#7150](https://github.com/scalameta/metals/pull/7150)
  ([tgodzik](https://github.com/tgodzik))
- improvement: look for definition in pc only for local symbols in the current tree
  [\#7105](https://github.com/scalameta/metals/pull/7105)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): bump undici from 6.19.8 to 6.21.1 in /website
  [\#7147](https://github.com/scalameta/metals/pull/7147)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- docs: release notes for Metals 1.5.0
  [\#7124](https://github.com/scalameta/metals/pull/7124)
  ([kasiaMarek](https://github.com/kasiaMarek))
