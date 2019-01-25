# Changelog

## [v0.4.0](https://github.com/scalameta/metals/tree/v0.4.0) (2019-01-25)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.3.3...v0.4.0)

**Fixed bugs:**

- Handle sbt pluginsDirectory as symbolic link
  [\#417](https://github.com/scalameta/metals/pull/417)
  ([gabro](https://github.com/gabro))

**Merged pull requests:**

- Trigger didFocus when current compile may affect focused buffer.
  [\#500](https://github.com/scalameta/metals/pull/500)
  ([olafurpg](https://github.com/olafurpg))
- Several polish improvements to workspace symbols
  [\#498](https://github.com/scalameta/metals/pull/498)
  ([olafurpg](https://github.com/olafurpg))
- Update sublime doc for manual build import trigger
  [\#495](https://github.com/scalameta/metals/pull/495)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Simplify test-workspace build.
  [\#492](https://github.com/scalameta/metals/pull/492)
  ([olafurpg](https://github.com/olafurpg))
- Update feature_request.md
  [\#491](https://github.com/scalameta/metals/pull/491)
  ([gabro](https://github.com/gabro))
- Avoid redundant didFocus compiles, fixes \#483.
  [\#488](https://github.com/scalameta/metals/pull/488)
  ([olafurpg](https://github.com/olafurpg))
- Polish before v0.4 release.
  [\#485](https://github.com/scalameta/metals/pull/485)
  ([olafurpg](https://github.com/olafurpg))
- Update Emacs docs [\#484](https://github.com/scalameta/metals/pull/484)
  ([JesusMtnez](https://github.com/JesusMtnez))
- Upgrade to Bloop v1.2.4 [\#481](https://github.com/scalameta/metals/pull/481)
  ([olafurpg](https://github.com/olafurpg))
- Clean up indexing pipeline.
  [\#480](https://github.com/scalameta/metals/pull/480)
  ([olafurpg](https://github.com/olafurpg))
- Avoid duplicate classpath indexing.
  [\#477](https://github.com/scalameta/metals/pull/477)
  ([olafurpg](https://github.com/olafurpg))
- Support navigation for visited dependency sources via workspace/symbol.
  [\#476](https://github.com/scalameta/metals/pull/476)
  ([olafurpg](https://github.com/olafurpg))
- Limit classpath search to jars.
  [\#475](https://github.com/scalameta/metals/pull/475)
  ([olafurpg](https://github.com/olafurpg))
- Small fixes to workspace symbol
  [\#472](https://github.com/scalameta/metals/pull/472)
  ([olafurpg](https://github.com/olafurpg))
- Implement workspace/symbol to search symbol by name.
  [\#471](https://github.com/scalameta/metals/pull/471)
  ([olafurpg](https://github.com/olafurpg))
- Implement fast, low-overhead and synthetics-aware find references.
  [\#469](https://github.com/scalameta/metals/pull/469)
  ([olafurpg](https://github.com/olafurpg))
- Add "Cascade compile" and "Cancel compile" tasks.
  [\#467](https://github.com/scalameta/metals/pull/467)
  ([olafurpg](https://github.com/olafurpg))
- Improve "No SemanticDB" error message
  [\#466](https://github.com/scalameta/metals/pull/466)
  ([olafurpg](https://github.com/olafurpg))
- Improve the status bar redirection to window/logMessage
  [\#465](https://github.com/scalameta/metals/pull/465)
  ([olafurpg](https://github.com/olafurpg))
- Lower recommended memory settings.
  [\#464](https://github.com/scalameta/metals/pull/464)
  ([olafurpg](https://github.com/olafurpg))
- Disable SbtSlowSuite in Appveyor CI.
  [\#460](https://github.com/scalameta/metals/pull/460)
  ([olafurpg](https://github.com/olafurpg))
- Improve diagnostic reporting.
  [\#459](https://github.com/scalameta/metals/pull/459)
  ([olafurpg](https://github.com/olafurpg))
- Add test case for "missing scalafmt version"
  [\#458](https://github.com/scalameta/metals/pull/458)
  ([olafurpg](https://github.com/olafurpg))
- Use scalafmt-dynamic module to simplify formatting implementation.
  [\#452](https://github.com/scalameta/metals/pull/452)
  ([olafurpg](https://github.com/olafurpg))
- Improve compilation tracking in the status bar.
  [\#451](https://github.com/scalameta/metals/pull/451)
  ([olafurpg](https://github.com/olafurpg))
- Improve eglot documentation
  [\#450](https://github.com/scalameta/metals/pull/450)
  ([JesusMtnez](https://github.com/JesusMtnez))
- Feature/appveyor memory limits
  [\#449](https://github.com/scalameta/metals/pull/449)
  ([PanAeon](https://github.com/PanAeon))
- Preinit sbt on appveyor, because it usually fails the first time
  [\#448](https://github.com/scalameta/metals/pull/448)
  ([PanAeon](https://github.com/PanAeon))
- Add note to Emacs docs about Eglot usage
  [\#446](https://github.com/scalameta/metals/pull/446)
  ([olafurpg](https://github.com/olafurpg))
- Removed ansi colors & fixed TimerSuite for non standard locales
  [\#445](https://github.com/scalameta/metals/pull/445)
  ([entangled90](https://github.com/entangled90))
- update sublime doc to reflect the changes in the LSP plugin
  [\#443](https://github.com/scalameta/metals/pull/443)
  ([ayoub-benali](https://github.com/ayoub-benali))
- Upgrade to mdoc v1.0.0 [\#442](https://github.com/scalameta/metals/pull/442)
  ([olafurpg](https://github.com/olafurpg))
- add snapshot resolver since we're publishing snapshots
  [\#441](https://github.com/scalameta/metals/pull/441)
  ([mpollmeier](https://github.com/mpollmeier))
- Document textDocument/documentSymbol and textDocument/formatting
  [\#440](https://github.com/scalameta/metals/pull/440)
  ([gabro](https://github.com/gabro))
- Remove -Dmetals.documentSymbol in favor of client capabilities
  [\#439](https://github.com/scalameta/metals/pull/439)
  ([gabro](https://github.com/gabro))
- Fix wrong log [\#437](https://github.com/scalameta/metals/pull/437)
  ([gabro](https://github.com/gabro))
- Add log when skipping formatting of a file due to Scalafmt configuration
  [\#436](https://github.com/scalameta/metals/pull/436)
  ([gabro](https://github.com/gabro))
- Don't attempt build import if sbt version is \< 0.13.17
  [\#434](https://github.com/scalameta/metals/pull/434)
  ([gabro](https://github.com/gabro))
- Implement textDocument/formatting using Scalafmt
  [\#429](https://github.com/scalameta/metals/pull/429)
  ([gabro](https://github.com/gabro))
- Move critical file watching in-house.
  [\#427](https://github.com/scalameta/metals/pull/427)
  ([olafurpg](https://github.com/olafurpg))
- Implement textDocument/documentSymbols
  [\#424](https://github.com/scalameta/metals/pull/424)
  ([gabro](https://github.com/gabro))
- Add test to CreateDirectoriesSuite
  [\#421](https://github.com/scalameta/metals/pull/421)
  ([gabro](https://github.com/gabro))
- Improve Emacs docs [\#419](https://github.com/scalameta/metals/pull/419)
  ([JesusMtnez](https://github.com/JesusMtnez))
- Fix incorrect docs [\#418](https://github.com/scalameta/metals/pull/418)
  ([olafurpg](https://github.com/olafurpg))
- Add more steps to release process.
  [\#414](https://github.com/scalameta/metals/pull/414)
  ([olafurpg](https://github.com/olafurpg))

\* _This Changelog was automatically generated by
[github_changelog_generator](https://github.com/github-changelog-generator/github-changelog-generator)_
