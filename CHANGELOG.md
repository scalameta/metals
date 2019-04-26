# Changelog

## [v0.5.1](https://github.com/scalameta/metals/tree/v0.5.1) (2019-04-26)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.5.0...v0.5.1)

**Merged pull requests:**

- Upgrade to latest Scalameta.
  [\#702](https://github.com/scalameta/metals/pull/702)
  ([olafurpg](https://github.com/olafurpg))
- Add question template [\#701](https://github.com/scalameta/metals/pull/701)
  ([gabro](https://github.com/gabro))
- Don't return workspace/symbol results for deleted files.
  [\#695](https://github.com/scalameta/metals/pull/695)
  ([olafurpg](https://github.com/olafurpg))
- Insert global imports instead of local imports.
  [\#692](https://github.com/scalameta/metals/pull/692)
  ([olafurpg](https://github.com/olafurpg))
- Fix a dead link to Bloop site
  [\#691](https://github.com/scalameta/metals/pull/691)
  ([gabro](https://github.com/gabro))
- Update a couple .lines to .linesIterator for java11
  [\#688](https://github.com/scalameta/metals/pull/688)
  ([er1c](https://github.com/er1c))
- Fix typo [\#687](https://github.com/scalameta/metals/pull/687)
  ([greenrd](https://github.com/greenrd))
- Handle document symbols for nested declarations
  [\#686](https://github.com/scalameta/metals/pull/686)
  ([gabro](https://github.com/gabro))
- Update Emacs docs [\#681](https://github.com/scalameta/metals/pull/681)
  ([JesusMtnez](https://github.com/JesusMtnez))
- Add "Coming from IntelliJ" section to VS Code docs.
  [\#678](https://github.com/scalameta/metals/pull/678)
  ([olafurpg](https://github.com/olafurpg))
- Clear diagnostics on build import, fixes \#644.
  [\#677](https://github.com/scalameta/metals/pull/677)
  ([olafurpg](https://github.com/olafurpg))
- Disable flaky test on Appveyor.
  [\#676](https://github.com/scalameta/metals/pull/676)
  ([olafurpg](https://github.com/olafurpg))
- Update stable version in issue template
  [\#671](https://github.com/scalameta/metals/pull/671)
  ([gabro](https://github.com/gabro))
- Documenting coc.nvim as the recommended LSP client for Vim
  [\#665](https://github.com/scalameta/metals/pull/665)
  ([gvolpe](https://github.com/gvolpe))
- Remove Deprecated Usage Of `java.util.Date` API
  [\#663](https://github.com/scalameta/metals/pull/663)
  ([isomarcte](https://github.com/isomarcte))
- Link to vscode marketplace
  [\#661](https://github.com/scalameta/metals/pull/661)
  ([raboof](https://github.com/raboof))
- Use correct GIF for signature help on landing page.
  [\#660](https://github.com/scalameta/metals/pull/660)
  ([olafurpg](https://github.com/olafurpg))
- Document new features and write release announcement.
  [\#655](https://github.com/scalameta/metals/pull/655)
  ([olafurpg](https://github.com/olafurpg))
- Make detail view in override not show unicode character.
  [\#654](https://github.com/scalameta/metals/pull/654)
  ([tgodzik](https://github.com/tgodzik))


## [v0.5.0](https://github.com/scalameta/metals/tree/v0.5.0) (2019-04-12)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.4.4...v0.5.0)

**Merged pull requests:**

- Welcome Marek and Tomasz to the team! [\#646](https://github.com/scalameta/metals/pull/646) ([olafurpg](https://github.com/olafurpg))
- Implement goto definition fallback with the presentation compiler. [\#645](https://github.com/scalameta/metals/pull/645) ([olafurpg](https://github.com/olafurpg))
- Generate exhaustive pattern match for sealed types. [\#643](https://github.com/scalameta/metals/pull/643) ([olafurpg](https://github.com/olafurpg))
- Improve quality and performance of workspace/symbol, fixes \#639. [\#642](https://github.com/scalameta/metals/pull/642) ([olafurpg](https://github.com/olafurpg))
- Enable workspace query to match package object [\#641](https://github.com/scalameta/metals/pull/641) ([mudsam](https://github.com/mudsam))
- Make completions work on overr\<COMPLETE\> and def\<COMPLETE\> [\#640](https://github.com/scalameta/metals/pull/640) ([tgodzik](https://github.com/tgodzik))
- Give CompletionPosition finer control over sorting, fixes \#619. [\#638](https://github.com/scalameta/metals/pull/638) ([olafurpg](https://github.com/olafurpg))
- Several UX improvements  [\#637](https://github.com/scalameta/metals/pull/637) ([olafurpg](https://github.com/olafurpg))
- Use fallback presentation compiler for missing build target. [\#634](https://github.com/scalameta/metals/pull/634) ([olafurpg](https://github.com/olafurpg))
- Fix six bugs [\#633](https://github.com/scalameta/metals/pull/633) ([olafurpg](https://github.com/olafurpg))
- Implement textDocument/foldingRange [\#632](https://github.com/scalameta/metals/pull/632) ([marek1840](https://github.com/marek1840))
- Provide fuzzier type member matching and more polished sorting. [\#629](https://github.com/scalameta/metals/pull/629) ([olafurpg](https://github.com/olafurpg))
- Sort symbols defined in the current file to the top, fixes \#618. [\#628](https://github.com/scalameta/metals/pull/628) ([olafurpg](https://github.com/olafurpg))
- Render @see as links. Improve inline link handling and update test cases. [\#627](https://github.com/scalameta/metals/pull/627) ([mudsam](https://github.com/mudsam))
- Disable macro-paradise compiler plugin, fixes \#622. [\#625](https://github.com/scalameta/metals/pull/625) ([olafurpg](https://github.com/olafurpg))
- Improve completion snippets, fixes \#610. [\#623](https://github.com/scalameta/metals/pull/623) ([olafurpg](https://github.com/olafurpg))
- Implement textDocument/documentHighlight [\#621](https://github.com/scalameta/metals/pull/621) ([tgodzik](https://github.com/tgodzik))
- Handle exceptions when computing completion position. [\#616](https://github.com/scalameta/metals/pull/616) ([olafurpg](https://github.com/olafurpg))
- Don't eagerly load presentation compiler during tests. [\#614](https://github.com/scalameta/metals/pull/614) ([olafurpg](https://github.com/olafurpg))
- Eagerly load presentation compiler for open buffers. [\#605](https://github.com/scalameta/metals/pull/605) ([olafurpg](https://github.com/olafurpg))
- Eagerly load Scalafmt during initialized [\#604](https://github.com/scalameta/metals/pull/604) ([olafurpg](https://github.com/olafurpg))
- Complete filename when defining toplevel class/trait/object [\#603](https://github.com/scalameta/metals/pull/603) ([olafurpg](https://github.com/olafurpg))
- Remove signature help fallback in hover. [\#602](https://github.com/scalameta/metals/pull/602) ([olafurpg](https://github.com/olafurpg))
- Fix \#599, don't insert import above generated parameter accessors [\#600](https://github.com/scalameta/metals/pull/600) ([olafurpg](https://github.com/olafurpg))
- Several small fixes [\#597](https://github.com/scalameta/metals/pull/597) ([olafurpg](https://github.com/olafurpg))
- Support navigation for library dependencies in Scala versions. [\#596](https://github.com/scalameta/metals/pull/596) ([olafurpg](https://github.com/olafurpg))
- Implement hover \(aka. type at point\). [\#595](https://github.com/scalameta/metals/pull/595) ([olafurpg](https://github.com/olafurpg))
- Fix auto-import position around definition annotations, fixes \#593 [\#594](https://github.com/scalameta/metals/pull/594) ([olafurpg](https://github.com/olafurpg))
- Provide completions on `case` for valid subclasses. [\#592](https://github.com/scalameta/metals/pull/592) ([olafurpg](https://github.com/olafurpg))
- Polish snippets when completing with existing parentheses and braces [\#590](https://github.com/scalameta/metals/pull/590) ([olafurpg](https://github.com/olafurpg))
- Handle generic unapply signatures in signature help. [\#589](https://github.com/scalameta/metals/pull/589) ([olafurpg](https://github.com/olafurpg))
- Explicitly set filter text for all completions. [\#588](https://github.com/scalameta/metals/pull/588) ([olafurpg](https://github.com/olafurpg))
- Add server property config to disable features. [\#587](https://github.com/scalameta/metals/pull/587) ([olafurpg](https://github.com/olafurpg))
- Add support for non-Lightbend compilers [\#586](https://github.com/scalameta/metals/pull/586) ([tindzk](https://github.com/tindzk))
- Fix signature help bug for tuple patterns. [\#585](https://github.com/scalameta/metals/pull/585) ([olafurpg](https://github.com/olafurpg))
- Escape keyword identifier in packag prefixes. [\#584](https://github.com/scalameta/metals/pull/584) ([olafurpg](https://github.com/olafurpg))
- Filter out ensuring and -\> extension methods from Predef. [\#583](https://github.com/scalameta/metals/pull/583) ([olafurpg](https://github.com/olafurpg))
- Include method signature in completion item label. [\#581](https://github.com/scalameta/metals/pull/581) ([olafurpg](https://github.com/olafurpg))
- Two completion improvements [\#579](https://github.com/scalameta/metals/pull/579) ([olafurpg](https://github.com/olafurpg))
- Insert local import when completing workspace symbol. [\#578](https://github.com/scalameta/metals/pull/578) ([olafurpg](https://github.com/olafurpg))
- Update Sublime Text doc regarding Goto symbol in workspace [\#577](https://github.com/scalameta/metals/pull/577) ([ayoub-benali](https://github.com/ayoub-benali))
- Include `case` completion when writing partial function on tuples. [\#576](https://github.com/scalameta/metals/pull/576) ([olafurpg](https://github.com/olafurpg))
- Restart the presentation compile more aggressively. [\#575](https://github.com/scalameta/metals/pull/575) ([olafurpg](https://github.com/olafurpg))
- Fix \#573, provide unique filter text for each interpolator completion item [\#574](https://github.com/scalameta/metals/pull/574) ([olafurpg](https://github.com/olafurpg))
- Fix \#569, remove completion items with `\_CURSOR\_` name. [\#572](https://github.com/scalameta/metals/pull/572) ([olafurpg](https://github.com/olafurpg))
- Implement `override def` completions. [\#570](https://github.com/scalameta/metals/pull/570) ([olafurpg](https://github.com/olafurpg))
- Make bloop server startup more robust, reuse sockets wherever possible. [\#566](https://github.com/scalameta/metals/pull/566) ([mudsam](https://github.com/mudsam))
- Implement type member selection in string interpolators. [\#563](https://github.com/scalameta/metals/pull/563) ([olafurpg](https://github.com/olafurpg))
- Update Atom info [\#561](https://github.com/scalameta/metals/pull/561) ([laughedelic](https://github.com/laughedelic))
- Improve string interpolator completions [\#560](https://github.com/scalameta/metals/pull/560) ([olafurpg](https://github.com/olafurpg))
- Remove root package from completion results. [\#559](https://github.com/scalameta/metals/pull/559) ([olafurpg](https://github.com/olafurpg))
- Resolve mtags from Sonatype Snapshots, fixes \#554. [\#558](https://github.com/scalameta/metals/pull/558) ([olafurpg](https://github.com/olafurpg))
- Disable `\(` as commit character. [\#557](https://github.com/scalameta/metals/pull/557) ([olafurpg](https://github.com/olafurpg))
- Wrap identifiers in backticks when necessary. [\#556](https://github.com/scalameta/metals/pull/556) ([olafurpg](https://github.com/olafurpg))
- Trigger parameter hints command after completion, if supported. [\#552](https://github.com/scalameta/metals/pull/552) ([olafurpg](https://github.com/olafurpg))
- Cross-publish mtags for all supported Scala versions. [\#541](https://github.com/scalameta/metals/pull/541) ([olafurpg](https://github.com/olafurpg))
- Implement completions and signature help. [\#527](https://github.com/scalameta/metals/pull/527) ([olafurpg](https://github.com/olafurpg))

## [v0.4.1](https://github.com/scalameta/metals/tree/v0.4.1) (2019-02-01)

[Full Changelog](https://github.com/scalameta/metals/compare/v0.4.0...v0.4.1)

- Upgrade to Bloop v1.2.5 [\#513](https://github.com/scalameta/metals/pull/513)
  ([olafurpg](https://github.com/olafurpg))
- Respect workspace folders for Scalafmt formatting, fixes \#509.
  [\#512](https://github.com/scalameta/metals/pull/512)
  ([olafurpg](https://github.com/olafurpg))
- Fix navigation bug for var setters.
  [\#511](https://github.com/scalameta/metals/pull/511)
  ([olafurpg](https://github.com/olafurpg))
- Upgrade to Scalameta v4.1.3.
  [\#510](https://github.com/scalameta/metals/pull/510)
  ([olafurpg](https://github.com/olafurpg))
- Change behavior of FileWatcher so that it doesn't create non-existing source
  directories [\#506](https://github.com/scalameta/metals/pull/506)
  ([mudsam](https://github.com/mudsam))
- Reference pluginCrossBuild via reflection to support older sbt 0.13.
  [\#505](https://github.com/scalameta/metals/pull/505)
  ([olafurpg](https://github.com/olafurpg))

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

