---
author: Tomasz Godzik
title: Metals v0.11.1 - Aluminium
authorURL: https://twitter.com/TomekGodzik
authorImageURL: https://github.com/tgodzik.png
---

Metals v0.11.1 is a bugfix release needed to fix a couple of performance
regressions caused by some of the recent changes.

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">43</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">21</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">6</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">3</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">0</td>
  </tr>
</tbody>
</table>

For full details: https://github.com/scalameta/metals/milestone/46?closed=1

Metals is a language server for Scala that works with VS Code, Vim, Emacs,
Sublime Text and Eclipse. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from [Lunatech](https://lunatech.com) along with contributors from
the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

- Reenabled test explorer as the default in Visual Studio Code (thanks
  [kpodsiad](https://github.com/kpodsiad) for the quick fix!)

## Miscellaneous

- Fix test explorer performance issues. [kpodsiad](https://github.com/kpodsiad)
- Fix performance in large files. [tgodzik](https://github.com/tgodzik)
- fix user config for java formatting. [Arthurm1](https://github.com/Arthurm1)
- Call javap with `-private` flag. [durban](https://github.com/durban)
- Fix issues when using Metals analyze with inner classes. [durban](https://github.com/durban)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v0.11.0..v0.11.1
Scala Steward
Tomasz Godzik
Kamil Podsiadlo
Vadim Chelyshov
Arthur McGibbon
Daniel Urban
```

## Merged PRs

## [v0.11.1](https://github.com/scalameta/metals/tree/v0.11.1) (2022-01-17)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.11.0...v0.11.1)

**Merged pull requests:**

- Revert scribe update [\#3529](https://github.com/scalameta/metals/pull/3529)
  ([tgodzik](https://github.com/tgodzik))
- refactor: Print path for which snapshot couldn't be loaded
  [\#3525](https://github.com/scalameta/metals/pull/3525)
  ([kpodsiad](https://github.com/kpodsiad))
- docs: Test Explorer [\#3528](https://github.com/scalameta/metals/pull/3528)
  ([kpodsiad](https://github.com/kpodsiad))
- Fix ClassFinder bug with inner classes
  [\#3522](https://github.com/scalameta/metals/pull/3522)
  ([durban](https://github.com/durban))
- Call javap with -private flag
  [\#3523](https://github.com/scalameta/metals/pull/3523)
  ([durban](https://github.com/durban))
- Update ammonite-util to 2.5.1
  [\#3516](https://github.com/scalameta/metals/pull/3516)
  ([scala-steward](https://github.com/scala-steward))
- Fix test explorer performance issues
  [\#3510](https://github.com/scalameta/metals/pull/3510)
  ([kpodsiad](https://github.com/kpodsiad))
- Update scalafix-interfaces to 0.9.34
  [\#3521](https://github.com/scalameta/metals/pull/3521)
  ([scala-steward](https://github.com/scala-steward))
- Update flyway-core to 8.4.1
  [\#3520](https://github.com/scalameta/metals/pull/3520)
  ([scala-steward](https://github.com/scala-steward))
- Update scribe, scribe-file, scribe-slf4j to 3.6.9
  [\#3519](https://github.com/scalameta/metals/pull/3519)
  ([scala-steward](https://github.com/scala-steward))
- Update ujson to 1.4.4 [\#3518](https://github.com/scalameta/metals/pull/3518)
  ([scala-steward](https://github.com/scala-steward))
- Update mill-contrib-testng to 0.10.0
  [\#3517](https://github.com/scalameta/metals/pull/3517)
  ([scala-steward](https://github.com/scala-steward))
- Update metaconfig-core to 0.9.16
  [\#3514](https://github.com/scalameta/metals/pull/3514)
  ([scala-steward](https://github.com/scala-steward))
- Update sbt-scalafix, scalafix-interfaces to 0.9.34
  [\#3513](https://github.com/scalameta/metals/pull/3513)
  ([scala-steward](https://github.com/scala-steward))
- Use ArraySeq instead of List for Diffutils
  [\#3512](https://github.com/scalameta/metals/pull/3512)
  ([tgodzik](https://github.com/tgodzik))
- fix user config for java formatting
  [\#3504](https://github.com/scalameta/metals/pull/3504)
  ([Arthurm1](https://github.com/Arthurm1))
- [JavaInteractiveSemanticdb] Fix Jdk version parsing
  [\#3505](https://github.com/scalameta/metals/pull/3505)
  ([dos65](https://github.com/dos65))
- JavaInteractiveSemanticDb - plugin version fix
  [\#3499](https://github.com/scalameta/metals/pull/3499)
  ([dos65](https://github.com/dos65))
- [Github] switch to the latest release in bug reports and workflows
  [\#3502](https://github.com/scalameta/metals/pull/3502)
  ([dos65](https://github.com/dos65))
- Add release notes for Metals 0.11.0
  [\#3493](https://github.com/scalameta/metals/pull/3493)
  ([tgodzik](https://github.com/tgodzik))
- Remove workaround for the wrong Java semanticdb md5 hash
  [\#3498](https://github.com/scalameta/metals/pull/3498)
  ([tgodzik](https://github.com/tgodzik))
