---
id: overview
title: Text Editors
sidebar_label: Overview
slug: /
---

## Latest Metals server versions

To find out how to set the version in your editor please check out the editor
specific sections.

```scala mdoc:releases

```

Snapshot releases are not guaranteed to work.

## Editor support

Metals works with the following text editors with varying degree of
functionality.

<table>
<thead>
  <tr>
    <td />
    <td align="center">Visual Studio Code</td>
    <td align="center">Vim</td>
    <td align="center">Sublime Text</td>
    <td align="center">Emacs</td>
  </tr>
</thead>
<tbody>
  <tr>
    <td>Installation</td>
    <td align="center">Single click</td>
    <td align="center">Single click</td>
    <td align="center">Single click</td>
    <td align="center">Few steps</td>
  </tr>
  <tr>
    <td>Build import</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Diagnostics</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Goto definition</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Completions</td>
    <td align="center">✅</td>
    <td align="center">✅*</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Hover</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Hover for selection</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">❌</td>
  </tr>
  <tr>
    <td>Parameter hints</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Find references</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Run/Debug</td>
    <td align="center">✅</td>
    <td align="center"></td>
    <td align="center"></td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Find implementations</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Rename symbol</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Code actions</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Worksheets</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">Comments</td>
  </tr>
  <tr>
    <td>Document symbols</td>
    <td align="center">✅</td>
    <td align="center">Flat</td>
    <td align="center">Flat</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Workspace symbols</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Formatting</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Folding</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center"> </td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Highlight</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Metals Extensions</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">Status bar, Input box, Decoration protocol, Did focus</td>
    <td align="center">Status bar</td>
  </tr>
  <tr>
     <td>Organize imports</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
  </tr>
  <tr>
     <td>Implicit decorations</td>
     <td align="center">✅</td>
     <td align="center">Shown in hover</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
  </tr>
  <tr>
     <td>Source file analyzer</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">❌</td>
  </tr>
  <tr>
     <td>Find text in dependency JAR files</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">❌</td>
  </tr>
  <tr>
     <td>Run scalafix rules</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
     <td align="center">✅</td>
  </tr>
</tbody>
</table>

## Installation

**Single click**: Metals is easy to install and requires minimal configuration
out-of-the-box.

**Few steps**: installing Metals requires a few custom steps and minimal
configuration to work.

_You can find instructions on how to install Metals for your editor on its
specific page._

## Build import

**✅**: it is possible to import a build such as an sbt project directly from
the editor.

**Requires browser**: importing a build requires additional steps in a web
browser using a localhost server. It is not possible to import a build within
the editor.

## Diagnostics

**✅**: Diagnostics are correctly published on compile.

Compile errors are reported as red squiggles in the editor. Compilation is
triggered on file save for the build target (project/module) containing the
focused text file.

![Diagnostics](https://user-images.githubusercontent.com/1408093/48774587-f4d5c780-ecca-11e8-8087-acca5a05ca78.png)

### Known limitations

- Slow feedback for type errors. Syntax errors are published as you type but
  type errors are handled by the build tool, meaning diagnostics may take a
  while to publish for large projects.

## Goto definition

Navigate to symbol definitions for project sources and Scala/Java library
dependencies.

Symbols are resolved according to the last successful compilation in the build
tool and navigation continues to work despite syntax errors in the open unsaved
buffer.

![Goto Definition](https://user-images.githubusercontent.com/1408093/48776422-1f764f00-ecd0-11e8-96d1-170f2354d50e.gif)

### Known limitations

- Navigation does not work for buffers that do not tokenize, for example due to
  unclosed string literals.
- [scalameta/scalameta#1802](https://github.com/scalameta/scalameta/issues/1802)
  reflective invocations (methods calls on structural types) do not resolve to a
  definition.

## Completions

Use code completions to explore APIs, implement interfaces, generate exhaustive
pattern matches and more.

![2019-04-12 14 19 39](https://user-images.githubusercontent.com/1408093/56036958-725bac00-5d2e-11e9-9cf7-46249125494a.gif)

- **Auto-import**: imports are inserted at the bottom of the global import list.
  Imports still need to be sorted and grouped manually, we are exploring ways to
  automate this workflow in the future.
- **Override def**: implement methods from the super class.
- **Exhaustive match**: generate an exhaustive pattern match for sealed types.
- **String interpolator**: automatically convert string literals into string
  interpolators.
- **Filename**: complete classnames based on the enclosing file.
- **Documentation**: read the docstring for method symbols by pressing
  ctrl+space in VS Code.

### Known limitations

- completion results don't include symbols that have just been typed in separate
  files without a successful compilation in the build tool.

## Hover (aka. type at point)

See the expression type and symbol signature under the cursor.

![](https://i.imgur.com/2MfQvsM.gif)

- **Expression type**: shows the non-generic type of the highlighted expression.
- **Symbol signature**: shows the generic signature of symbol under the cursor
  along with its docstring, if available.

## Signature help (aka. parameter hints)

View a method signature and method overloads as you fill in the arguments.

![](https://i.imgur.com/DAWIrHu.gif)

## Find references

Find symbol references in project sources. References include implicits,
inferred `.apply`, desugared `.flatMap` from for comprehensions and other
symbols that may not be explicitly written in source, making it possible to
discover usages of difficult-to-grep symbols. The Metals navigation index is
low-overhead and should only require a few megabytes of memory even for large
projects.

![Find references](https://user-images.githubusercontent.com/1408093/51089190-75fc8880-1769-11e9-819c-95262205e95c.png)

### Known limitations

- References to overridden methods are not included in the results. For example,
  if you run "find references" on the method `Dog.name()` then it won't include
  references to the super method `Animal.name()`.

## Worksheets

**✅**: Worksheets work via the Decoration protocol and are added as a
non-editable side decoration.

**Comments**: Worksheets work via `workspace/applyEdit` by adding comments to
the source code and support hover to show larger output. You can find more
information about worksheets under the editor specific worksheet section. For
example, [here for VS Code](vscode.md#worksheets).

## Document symbols

**✅**: Document symbols are displayed in a hierarchical outline.

**Flat**: Document symbols are displayed in a flat outline.

![Document Symbols](https://user-images.githubusercontent.com/1408093/50635569-014c7180-0f53-11e9-8898-62803898781c.gif)

## Workspace symbols

Fuzzy search a symbol in the workspace of library dependencies by its name.

- All-lowercase queries are treated as case-insensitive searches.
- Queries ending with a dot `.` list nested symbols.
- Queries containing a semicolon `;` search library dependencies.

![Fuzzy symbol search example](https://i.imgur.com/w5yrK1w.gif)

## Formatting

Metals uses Scalafmt to respond to formatting requests from the editor,
according to the configuration defined in `.scalafmt.conf`.

Learn how to configure Scalafmt at
https://scalameta.org/scalafmt/docs/configuration.html.

![Formatting](https://user-images.githubusercontent.com/1408093/50635748-b0894880-0f53-11e9-913b-acfd5f505351.gif)

## Code folding

Fold ranges such as large multi-line expressions, import groups and comments.

![](https://camo.githubusercontent.com/3fdd7ae28907ac61c0a1ac5fdc07d085245957aa/68747470733a2f2f692e696d6775722e636f6d2f667149554a54472e676966)

## Document highlight

Highlight references to the same symbol in the open file.

![](https://i.imgur.com/0uhc9P5.gif)

## Package explorer

Browse packages, classes and methods in the workspace and library dependencies
using the Metals sidebar. This feature is only implemented in VS Code.

## Test Explorer

Test Explorer is a feature that allows editors to display tests as a separate
tree representation of tests. Although it was implemented in order to use Visual
Studio Code's
[Testing API](https://code.visualstudio.com/api/extension-guides/testing). The Test
Explorer API is editor agnostic and can be used by other editors than just VS
Code. ![test-explorer](https://i.imgur.com/Z3VtS0O.gif)

Work on the Test Explorer is still in progress and the feature has some known
limitations:

- Test Explorer is able to discover single test cases only for JUnit4 test
  classes. Support for other test frameworks is being worked on.
- detecting suites in cross scala-version projects is inconsistent, see
  [this issue](https://github.com/scalameta/metals/issues/3503).
- there is no support for JS and Native platforms. For any changes subscribe to
  the related
  [feature request](https://github.com/scalameta/metals-feature-requests/issues/256).

You can find more information about Test Explorer under the
[VS Code](vscode.md#test-explorer) specific section.

### Running Tests

Both run and debug under the hood use BSP's debug request. More information
about it can be found at
[Bloop DAP diagram](https://github.com/scalacenter/bloop/blob/master/docs/assets/dap-example-metals.png)
or
[BSP specification](https://build-server-protocol.github.io/docs/specification.html#debug-request)
website.

## Metals Extensions

**Status bar**: Editor client implements the `metals/status` notification.

**Decoration protocol**: Editor client implements the
[Decoration Protocol](../integrations/decoration-protocol.md).

**Tree view**: Editor client implements the
[Tree View Protocol](../integrations/tree-view-protocol.md).

**Did focus**: Editor client implements the `metals/didFocusTextDocument`
notification.

**Slow task**: Editor client implements the `metals/slowTask` request.

**Input box**: Editor client implements the `metals/inputBox` request.

**Quick pick**: Editor client implements the `metals/quickPick` request.

**Window state**: Editor client implements the `metals/windowStateDidChange`
notification.

**✅**: Editor implements all Metals extension endpoints.

The Metals language server supports custom extensions that are not part of the
Language Server Protocol (LSP). These extensions are not necessary for Metals to
function but they improve the user experience. To learn more about Metals
extensions, see [integrating a new editor](../integrations/new-editor.md).

## Implicit decorations

**✅**: Additional information inferred from the code can be show within the
code using virtual text.

**Shown in hover**: Additional information inferred from the code can be show
when hovering over a specific line. That hover only shows the additional symbols
on the current line.

## Additional file types

Not all features are supported in all possible scenarios, especially when it
comes to non-standard Scala files like Ammonite scripts, worksheets or sbt
scripts.

<table>
<thead>
  <tr>
    <td />
    <td align="center">sbt scripts</td>
    <td align="center">Worksheets</td>
    <td align="center">Ammonite scripts*</td>
    <td align="center">Standalone Scala files</td>
  </tr>
</thead>
<tbody>
   <tr>
    <td>Diagnostics</td>
    <td align="center">✅*</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅*</td>
  </tr>
  <tr>
    <td>Goto definition</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Completions</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Hover</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Parameter hints</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Find references</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Run/Debug</td>
    <td align="center"></td>
    <td align="center"></td>
    <td align="center"></td>
    <td align="center"></td>
  </tr>
  <tr>
    <td>Find implementations</td>
    <td align="center">✅</td>
    <td align="center"></td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Rename symbol</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Code actions</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Document symbols</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Workspace symbols</td>
    <td align="center">✅</td>
    <td align="center">All symbols are local</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Formatting</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Folding</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Highlight</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
     <td>Organize imports</td>
     <td align="center"></td>
     <td align="center"></td>
     <td align="center"></td>
     <td align="center"></td>
  </tr>
  <tr>
    <td>Implicit decorations</td>
    <td align="center">✅</td>
    <td align="center"></td>
    <td align="center">✅</td>
    <td align="center">✅</td>
  </tr>
  <tr>
    <td>Decode file (cfr, semanticdb, tasty, javap)</td>
    <td align="center"></td>
    <td align="center"></td>
    <td align="center"></td>
    <td align="center"></td>
  </tr>
</tbody>
</table>

\* Note that there are some specific Ammonite features that aren't supported
like [multi-stage](https://ammonite.io/#Multi-stageScripts) scripts. Currently
Ammonite support is also limited to Scala 2.

\* Diagnostics for sbt script and standalone Scala files will only show parsing
errors, but not diagnostics coming from the compiler.
