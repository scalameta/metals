---
authors: kmarek
title: Metals v1.5.3 - Strontium
---

We're happy to announce the release of Metals v1.5.3, which besides many bugfixes and improvements brings an MCP server implementation to Metals, allowing your AI agent to also use the information that Metals can provide. 

<table>
<tbody>
  <tr>
    <td>Commits since last release</td>
    <td align="center">135</td>
  </tr>
  <tr>
    <td>Merged PRs</td>
    <td align="center">89</td>
  </tr>
    <tr>
    <td>Contributors</td>
    <td align="center">24</td>
  </tr>
  <tr>
    <td>Closed issues</td>
    <td align="center">32</td>
  </tr>
  <tr>
    <td>New features</td>
    <td align="center">9</td>
  </tr>
</tbody>
</table>

For full details: [https://github.com/scalameta/metals/milestone/78?closed=1](https://github.com/scalameta/metals/milestone/78?closed=1)

Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,
Helix and Sublime Text. Metals is developed at the
[Scala Center](https://scala.epfl.ch/) and [VirtusLab](https://virtuslab.com)
with the help from contributors from the community.

## TL;DR

Check out [https://scalameta.org/metals/](https://scalameta.org/metals/), and
give Metals a try!

## Menu
- [MCP support](#mcp-support)
- [Best effort compilation](#best-effort-compilation)
- [New inlay hints](#new-inlay-hints)
  - [Named parameters](#named-parameters)
  - [By-name parameters](#by-name-parameters)
- [Remove invalid imports code action](#remove-invalid-imports-code-action)
- [Convert to named lambda parameters code action](#convert-to-named-lambda-parameters-code-action)
- [Support using directives in worksheets](#support-using-directives-in-worksheets)
- [Improve auto-fill arguments](#improve-auto-fill-arguments)
- [Discover tests for ZIO test framework](#discover-tests-for-zio-test-framework)
- [Miscellaneous](#miscellaneous)

## MCP support
Starting with this release, Metals can serve an SSE, MCP server.

### Why?
AI agents have the tendency to make up the existence of some functions, classes, arguments, and so on. If we allow them to use the information that Metals already has and provide them with such capabilities as searching for symbols, discovering class members, signature help, and getting symbol documentation, we expect AI agents to yield much more relevant suggestions.

Furthermore, giving the AI agent the capability to compile files, run tests, and import builds can further help automate your workflow.

### How to use Metals MCP?
To enable Metals MCP support, set `metals.startMcpServer` to `true`. For common editors like Cursor or VSCode (GitHub Copilot), MCP configuration will be automatically added to your workspace. However, you should still check in your AI agent settings that the connection was made successfully. For other AI, Metals will show a message upon MCP server startup with the port on which it was started locally. If you miss it, `Metals MCP server started on port: ${port}` message is also printed in the Metals log (`.metals/metals.log`).

### Current list of MCP tools:
- `compile-file` -- Compile a chosen Scala file.
- `compile-full` -- Compile the whole Scala project.
- `test` -- Run Scala test suite.
- `glob-search` -- Search for symbols containing substring
- `typed-glob-search` -- Search for symbols containing a substring that match the allowed symbol kinds (e.g., class, method).
- `inspect` -- Inspect a chosen Scala symbol. For packages, objects, and traits, it returns a list of members; for classes, it returns a list of members and constructors; and for methods, it returns signatures of all overloaded methods.
- `get-docs` -- Get documentation for a chosen Scala symbol
- `get-usages` -- Get usages for a chosen Scala symbol.
- `import-build` -- Import the build to IDE. Should be performed after any build changes, e.g., adding dependencies or any changes in build.sbt.

This feature is still experimental, and we'd love to get your feedback and improvement suggestions.

## Best effort compilation
Best effort compilation was first introduced to Metals `v1.3.4` (see [here](https://scalameta.org/metals/blog/2024/07/24/thallium#scala-3-best-effort-compilation)); however, due to multiple issues with it, it was disabled. Since then, a lot was fixed and improved in the area, so this release reintroduces best effort compilation behind a setting; simply set `metals.enableBestEffort` to `true` and give it a try.

## New inlay hints

### Named parameters
[tgodzik](https://github.com/tgodzik) added inlay hints for showing parameters' names on call site. The new inlay hint is available under `inlay-hints.named-parameters.enable` Metals setting, for now available only for Scala 2.

### By-name parameters
We also have new inlay hints marking by-name parameters with `=>` on the call site. Thanks go to [harpocrates](https://github.com/harpocrates).

## Remove invalid imports code action
This release also has a new code action for removing invalid imports, saving you the trouble of deleting them manually. It is yet another improvement added by [harpocrates](https://github.com/harpocrates). Code action works both for Scala 2 and Scala 3, though older Scala 2 versions might get only partial support.

## Convert to named lambda parameters code action

Thanks to [KacperFKorban](https://github.com/KacperFKorban) we have a new code action that converts a wildcard lambda into a lambda with parameters. This was only implemented for Scala 3 and will work from versions `3.7.2` and LTS `3.3.7`.

## Support using directives in worksheets

Since Metals has been supporting Scala-CLI for a while, and it is Metals's recommended tool for scripts, we are further embracing Scala-CLI style, and `using` directives are now supported also in the worksheets. Thanks go to [tgodzik](https://github.com/tgodzik) for adding this improvement.

## Improve auto-fill arguments

For a while now, Metals had a special completion for auto-filling argument names, however it was somewhat hidden. Now, the discoveribility of that feature was improved, and it naturally appear in the completion suggestion list. Thanks, [LiathHelvetica](https://github.com/LiathHelvetica) and [natsukagami](https://github.com/natsukagami), for making the change.

## Discover tests for ZIO test framework

With this release also comes support for discovering and running single test cases from Metals when using the ZIO test framework. Thanks go to [kaplan-shaked](https://github.com/kaplan-shaked), making this their first contribution!

## Miscellaneous
- improvement : Able to get environment variables from shell [ajafri2001](https://github.com/ajafri2001)
- improvement: prefer `.bazelproject` as the Bazel BSP root [harpocrates](https://github.com/harpocrates)
- improvement: add additional ScalaTest WordSpec types [tmilner](https://github.com/tmilner)
- fix: add backticks in hover output when needed [harpocrates](https://github.com/harpocrates)
- fix: show more precise signature help types [harpocrates](https://github.com/harpocrates)
- fix: consider build.mill and build.mill.scala when calculating Mill digest [sake92](https://github.com/sake92)
- fix: completions respect backticks at defn site [harpocrates](https://github.com/harpocrates)
- fix: don't rename if old and new packages are the same for package object [kasiaMarek](https://github.com/kasiaMarek)
- fix: add fallback presentation compiler for Java [kasiaMarek](https://github.com/kasiaMarek)
- fix: Add type param inlay hint for constuctor calls [kasiaMarek](https://github.com/kasiaMarek)
- improvement: add selection ranges for name [harpocrates](https://github.com/harpocrates)
- improvement: add folding range for function call [harpocrates](https://github.com/harpocrates)
- feat: respect LSP build target no-ide tag [ysedira](https://github.com/ysedira)
- improvement: queue or cancel previous connect request [kasiaMarek](https://github.com/kasiaMarek)
- fix & improvement: improvements in selection ranges [harpocrates](https://github.com/harpocrates)
- feat: convert string to interpolation on `${` [harpocrates](https://github.com/harpocrates)
- fix: don't remove non-existent entries from classpath [kasiaMarek](https://github.com/kasiaMarek)

## Contributors

Big thanks to everybody who contributed to this release or reported an issue!

```
$ git shortlog -sn --no-merges v1.5.2..v1.5.3
  19	Tomasz Godzik
  16	amit.miran
  14	Scalameta Bot
  13	Alec Theriault
  11	scalameta-bot
   8	ajafri2001
   6	Brice Jaglin
   6	dependabot[bot]
   6	kasiaMarek
   5	Kacper Korban
   5	Katarzyna Marek
   5	Tobias Roeser
   5	scarf
   3	Przemyslaw Wierzbicki
   3	Sakib Hadziavdic
   2	Myungbae Son
   1	Amit Miran
   1	Christopher Vogt
   1	Jens Kouros
   1	Maciej Dragun
   1	Michal Pawlik
   1	Shaked Kaplan
   1	Tom Milner
   1	ysedira
```

## Merged PRs

## [v1.5.3](https://github.com/scalameta/metals/tree/v1.5.3) (2025-05-13)

[Full Changelog](https://github.com/scalameta/metals/compare/v1.5.2...v1.5.3)

**Merged pull requests:**

- fix: don't remove non-existent entries from classpath
  [\#7445](https://github.com/scalameta/metals/pull/7445)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update cli_3, scala-cli-bsp from 1.7.1 to 1.8.0
  [\#7457](https://github.com/scalameta/metals/pull/7457)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mill-contrib-testng from 0.12.10 to 0.12.11
  [\#7455](https://github.com/scalameta/metals/pull/7455)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jackson-databind from 2.15.0 to 2.15.4
  [\#7454](https://github.com/scalameta/metals/pull/7454)
  ([scalameta-bot](https://github.com/scalameta-bot))
- MCP support
  [\#7390](https://github.com/scalameta/metals/pull/7390)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feat: folding ranges for function calls
  [\#7452](https://github.com/scalameta/metals/pull/7452)
  ([harpocrates](https://github.com/harpocrates))
- feat: selection ranges for more names
  [\#7446](https://github.com/scalameta/metals/pull/7446)
  ([harpocrates](https://github.com/harpocrates))
- build(deps): Update scala3-library from 3.3.5 to 3.3.6
  [\#7451](https://github.com/scalameta/metals/pull/7451)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update bloop-rifle from 2.0.9 to 2.0.10
  [\#7447](https://github.com/scalameta/metals/pull/7447)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update sbt-scalafix, scalafix-interfaces from 0.14.2 to 0.14.3
  [\#7448](https://github.com/scalameta/metals/pull/7448)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update directories, directories-jni from 0.1.2 to 0.1.3
  [\#7449](https://github.com/scalameta/metals/pull/7449)
  ([scalameta-bot](https://github.com/scalameta-bot))
- chore: Switch to coursier ProjectDirectories
  [\#7442](https://github.com/scalameta/metals/pull/7442)
  ([tgodzik](https://github.com/tgodzik))
- improvement : Able to get environment variables from shell
  [\#7301](https://github.com/scalameta/metals/pull/7301)
  ([ajafri2001](https://github.com/ajafri2001))
- refactor: pass report context to the presentation compiler
  [\#7428](https://github.com/scalameta/metals/pull/7428)
  ([kasiaMarek](https://github.com/kasiaMarek))
- improvement: queue or cancel previous connect request
  [\#6691](https://github.com/scalameta/metals/pull/6691)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feat: implement 'convert to named lambda parameters' code action
  [\#6669](https://github.com/scalameta/metals/pull/6669)
  ([KacperFKorban](https://github.com/KacperFKorban))
- build(deps): Update scalameta, semanticdb-metap, ... from 4.13.4 to 4.13.5
  [\#7439](https://github.com/scalameta/metals/pull/7439)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update scala-debug-adapter from 4.2.4 to 4.2.5
  [\#7434](https://github.com/scalameta/metals/pull/7434)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update metaconfig-core from 0.15.0 to 0.16.0
  [\#7437](https://github.com/scalameta/metals/pull/7437)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 11.7.2 to 11.8.0
  [\#7435](https://github.com/scalameta/metals/pull/7435)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update jsoup from 1.19.1 to 1.20.1
  [\#7436](https://github.com/scalameta/metals/pull/7436)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update munit from 1.1.0 to 1.1.1
  [\#7438](https://github.com/scalameta/metals/pull/7438)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps-dev): bump @types/node from 22.13.15 to 22.15.3 in /website
  [\#7432](https://github.com/scalameta/metals/pull/7432)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- feat: code action to remove invalid imports
  [\#7416](https://github.com/scalameta/metals/pull/7416)
  ([harpocrates](https://github.com/harpocrates))
- improvement: use specific jsonrpc code for compile errors
  [\#7423](https://github.com/scalameta/metals/pull/7423)
  ([cvogt](https://github.com/cvogt))
- chore: update the mill scripts to version `0.13.0-M2-3-ba7090`
  [\#7431](https://github.com/scalameta/metals/pull/7431)
  ([lefou](https://github.com/lefou))
- feat: read the Mill version from YAML frontmatter of the buildfile
  [\#7429](https://github.com/scalameta/metals/pull/7429)
  ([lefou](https://github.com/lefou))
- fix: Add type param inlay hint for constuctor calls
  [\#7425](https://github.com/scalameta/metals/pull/7425)
  ([kasiaMarek](https://github.com/kasiaMarek))
- Prepare for Scala 2.13.17
  [\#7421](https://github.com/scalameta/metals/pull/7421)
  ([kasiaMarek](https://github.com/kasiaMarek))
- tests: make HoverPlaintextSuite independent of stdlib Scaladoc
  [\#7426](https://github.com/scalameta/metals/pull/7426)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feature: Add inlay hints for named parameters
  [\#7400](https://github.com/scalameta/metals/pull/7400)
  ([tgodzik](https://github.com/tgodzik))
- Use fallback mtags and presentation compiler implementations in 2.12
  [\#7422](https://github.com/scalameta/metals/pull/7422)
  ([majk-p](https://github.com/majk-p))
- AddMissingImports for the entire file using codeActionKind.source
  [\#7391](https://github.com/scalameta/metals/pull/7391)
  ([amitmiran137](https://github.com/amitmiran137))
- improvement: Add best effort user configuration option
  [\#7396](https://github.com/scalameta/metals/pull/7396)
  ([tgodzik](https://github.com/tgodzik))
- bugfix: Make sure cs is an actual executable file
  [\#7420](https://github.com/scalameta/metals/pull/7420)
  ([tgodzik](https://github.com/tgodzik))
- chore: Add tooling spree mention
  [\#7418](https://github.com/scalameta/metals/pull/7418)
  ([tgodzik](https://github.com/tgodzik))
- fix: don't show byname hints for defaulted args
  [\#7417](https://github.com/scalameta/metals/pull/7417)
  ([harpocrates](https://github.com/harpocrates))
- fix(`DotEnvFileParser`): empty values or nonquoted values with comment
  [\#7414](https://github.com/scalameta/metals/pull/7414)
  ([nedsociety](https://github.com/nedsociety))
- build(deps): Update guava from 33.4.7-jre to 33.4.8-jre
  [\#7411](https://github.com/scalameta/metals/pull/7411)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update gradle-bloop from 1.6.2 to 1.6.3
  [\#7410](https://github.com/scalameta/metals/pull/7410)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mdoc-interfaces from 2.7.0 to 2.7.1
  [\#7413](https://github.com/scalameta/metals/pull/7413)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update flyway-core from 11.7.0 to 11.7.2
  [\#7412](https://github.com/scalameta/metals/pull/7412)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): bump http-proxy-middleware from 2.0.7 to 2.0.9 in /website
  [\#7409](https://github.com/scalameta/metals/pull/7409)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- feat: add inlay hints for byname parameters
  [\#7404](https://github.com/scalameta/metals/pull/7404)
  ([harpocrates](https://github.com/harpocrates))
- feat: add missing scala-cli deps identifiers
  [\#7407](https://github.com/scalameta/metals/pull/7407)
  ([scarf005](https://github.com/scarf005))
- feat: handle `% "provided"` on pasting sbt-style deps
  [\#7408](https://github.com/scalameta/metals/pull/7408)
  ([scarf005](https://github.com/scarf005))
- fix: add fallback presentation compiler for Java
  [\#7405](https://github.com/scalameta/metals/pull/7405)
  ([kasiaMarek](https://github.com/kasiaMarek))
- feat: handle `% Test` on pasting sbt-style deps
  [\#7402](https://github.com/scalameta/metals/pull/7402)
  ([scarf005](https://github.com/scarf005))
- fix & feat: improvements in selection ranges
  [\#7399](https://github.com/scalameta/metals/pull/7399)
  ([harpocrates](https://github.com/harpocrates))
- improvement: Support using directives in worksheets
  [\#7387](https://github.com/scalameta/metals/pull/7387)
  ([tgodzik](https://github.com/tgodzik))
- fix: don't rename if old and new packages are the same for package object
  [\#7395](https://github.com/scalameta/metals/pull/7395)
  ([kasiaMarek](https://github.com/kasiaMarek))
- build(deps): Update flyway-core from 11.6.0 to 11.7.0
  [\#7393](https://github.com/scalameta/metals/pull/7393)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update guava from 33.4.6-jre to 33.4.7-jre
  [\#7392](https://github.com/scalameta/metals/pull/7392)
  ([scalameta-bot](https://github.com/scalameta-bot))
- add support for ZIO test framework
  [\#7388](https://github.com/scalameta/metals/pull/7388)
  ([kaplan-shaked](https://github.com/kaplan-shaked))
- improvement: make diagnostics and debugging not supported for sbt message more clear
  [\#7389](https://github.com/scalameta/metals/pull/7389)
  ([kasiaMarek](https://github.com/kasiaMarek))
- bugfix: Fix issues with EOF during tokenization
  [\#7376](https://github.com/scalameta/metals/pull/7376)
  ([tgodzik](https://github.com/tgodzik))
- improvement: Forward all data from LSP
  [\#7385](https://github.com/scalameta/metals/pull/7385)
  ([tgodzik](https://github.com/tgodzik))
- feat: completions respect backticks at defn site
  [\#7379](https://github.com/scalameta/metals/pull/7379)
  ([harpocrates](https://github.com/harpocrates))
- bugfix: Fixed changed deps in lsp4j
  [\#7382](https://github.com/scalameta/metals/pull/7382)
  ([tgodzik](https://github.com/tgodzik))
- build(deps): bump estree-util-value-to-estree from 3.1.1 to 3.3.3 in /website
  [\#7380](https://github.com/scalameta/metals/pull/7380)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- feat: convert string to interpolation on `${`
  [\#7375](https://github.com/scalameta/metals/pull/7375)
  ([harpocrates](https://github.com/harpocrates))
- build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.16.0 to 3.16.1
  [\#7374](https://github.com/scalameta/metals/pull/7374)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Respect LSP build target no-ide tag
  [\#7368](https://github.com/scalameta/metals/pull/7368)
  ([ysedira](https://github.com/ysedira))
- docs: Add information about worksheet extension
  [\#7373](https://github.com/scalameta/metals/pull/7373)
  ([tomatitito](https://github.com/tomatitito))
- feat: infer dependency identifier on pasting sbt-style dependencies
  [\#7366](https://github.com/scalameta/metals/pull/7366)
  ([scarf005](https://github.com/scarf005))
- feat: `DownloadDependencies` gets debug tools
  [\#7369](https://github.com/scalameta/metals/pull/7369)
  ([harpocrates](https://github.com/harpocrates))
- improvement: improve arguments autofill
  [\#7367](https://github.com/scalameta/metals/pull/7367)
  ([LiathHelvetica](https://github.com/LiathHelvetica))
- build(deps): Update flyway-core from 11.5.0 to 11.6.0
  [\#7371](https://github.com/scalameta/metals/pull/7371)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update mill-contrib-testng from 0.12.9 to 0.12.10
  [\#7370](https://github.com/scalameta/metals/pull/7370)
  ([scalameta-bot](https://github.com/scalameta-bot))
- Consider build.mill and build.mill.scala when calculating Mill digest
  [\#7363](https://github.com/scalameta/metals/pull/7363)
  ([sake92](https://github.com/sake92))
- build(deps): bump image-size from 1.1.1 to 1.2.1 in /website
  [\#7362](https://github.com/scalameta/metals/pull/7362)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- #7320 fix package rename
  [\#7339](https://github.com/scalameta/metals/pull/7339)
  ([pshemass](https://github.com/pshemass))
- fix: show more precise signature help types
  [\#7357](https://github.com/scalameta/metals/pull/7357)
  ([harpocrates](https://github.com/harpocrates))
- feat: add CFR dep to `DownloadDependencies`
  [\#7358](https://github.com/scalameta/metals/pull/7358)
  ([harpocrates](https://github.com/harpocrates))
- build(deps): bump @easyops-cn/docusaurus-search-local from 0.48.5 to 0.49.2 in /website
  [\#7356](https://github.com/scalameta/metals/pull/7356)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- build(deps-dev): bump @types/node from 22.13.8 to 22.13.15 in /website
  [\#7355](https://github.com/scalameta/metals/pull/7355)
  ([dependabot[bot]](https://github.com/dependabot[bot]))
- fix: add backticks in hover output when needed
  [\#7354](https://github.com/scalameta/metals/pull/7354)
  ([harpocrates](https://github.com/harpocrates))
- chore: Add worksheet infinite loop test as flaky on windows
  [\#7353](https://github.com/scalameta/metals/pull/7353)
  ([tgodzik](https://github.com/tgodzik))
- docs: Update backporting docs
  [\#7351](https://github.com/scalameta/metals/pull/7351)
  ([tgodzik](https://github.com/tgodzik))
- improvement: add additional ScalaTest WordSpec types
  [\#7341](https://github.com/scalameta/metals/pull/7341)
  ([tmilner](https://github.com/tmilner))
- prefer `.bazelproject` as the Bazel BSP root
  [\#7349](https://github.com/scalameta/metals/pull/7349)
  ([harpocrates](https://github.com/harpocrates))
- build(deps): Update protobuf-java from 4.30.1 to 4.30.2
  [\#7345](https://github.com/scalameta/metals/pull/7345)
  ([scalameta-bot](https://github.com/scalameta-bot))
- remove mtags backpublishing adhoc code
  [\#7343](https://github.com/scalameta/metals/pull/7343)
  ([bjaglin](https://github.com/bjaglin))
- build(deps): Update flyway-core from 11.4.1 to 11.5.0
  [\#7346](https://github.com/scalameta/metals/pull/7346)
  ([scalameta-bot](https://github.com/scalameta-bot))
- build(deps): Update guava from 33.4.5-jre to 33.4.6-jre
  [\#7344](https://github.com/scalameta/metals/pull/7344)
  ([scalameta-bot](https://github.com/scalameta-bot))
- allow backpublishing with sbt-ci-release
  [\#7342](https://github.com/scalameta/metals/pull/7342)
  ([bjaglin](https://github.com/bjaglin))
- improvement: deduplicate file indexing in classfile symbol search
  [\#7337](https://github.com/scalameta/metals/pull/7337)
  ([kasiaMarek](https://github.com/kasiaMarek))
- docs: Add release notes for Metals 1.5.2
  [\#7319](https://github.com/scalameta/metals/pull/7319)
  ([tgodzik](https://github.com/tgodzik))
- sbt-metals: publish for & test against 2.0.0-M4
  [\#7340](https://github.com/scalameta/metals/pull/7340)
  ([bjaglin](https://github.com/bjaglin))
