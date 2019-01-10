---
id: overview
title: Text Editors
sidebar_label: Overview
---

Metals works with the following text editors with varying degree of
functionality.

<table>
<thead>
<tr>
  <td>Editor</td>
  <td align=center>Installation</td>
  <td align=center>Build import</td>
  <td align=center>Diagnostics</td>
  <td align=center>Goto definition</td>
  <td align=center>Document symbols</td>
  <td align=center>Formatting</td>
  <td align=center>Metals Extensions</td>
</tr>
</thead>
<tbody>
<tr>
  <td>Visual Studio Code</td>
  <td align=center>Single click</td>
  <td align=center>Built-in</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
</tr>
<tr>
  <td>Atom</td>
  <td align=center>Single click</td>
  <td align=center>Built-in</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center></td>
</tr>
<tr>
  <td>Vim</td>
  <td align=center>Few steps</td>
  <td align=center>Built-in</td>
  <td align=center>Escaped newlines</td>
  <td align=center>✅</td>
  <td align=center>Flat</td>
  <td align=center></td>
  <td align=center>Status bar</td>
</tr>
<tr>
  <td>Sublime Text 3</td>
  <td align=center>Few steps</td>
  <td align=center>Requires browser</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>Flat</td>
  <td align=center>✅</td>
  <td align=center></td>
</tr>
<tr>
  <td>Emacs</td>
  <td align=center>Few steps</td>
  <td align=center>Built-in</td>
  <td align=center>Single buffer</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>✅</td>
  <td align=center>Status bar</td>
</tr>
</tbody>
</table>

> Metals is a new project with limited features. If you are learning Scala or
> are looking for a rich IDE experience it is recommended to use IntelliJ
> instead.

## Installation

**Single click**: Metals is easy to install and requires minimal configuration
out-of-the-box.

**Few steps**: installing Metals requires a few custom steps and minimal
configuration to work.

**Compile from source**: installing Metals requires building an editor plugin
from source.

## Build import

**Built-in**: it is possible to import a build such as an sbt project directly
from the editor.

**Requires browser**: importing a build requires additional steps in a web
browser using a localhost server. It is not possible to import a build within
the editor.

## Diagnostics

**✅**: Diagnostics are correctly published on compile.

**Escaped newlines**: Multi-line diagnostic are slightly difficult to read since
newlines are escaped into `^@` characters.

**Single buffer**: Diagnostics are only published for the current buffer so
compile errors are lost for unopened files.

Compile errors are reported as red squiggles in the editor. Compilation is
triggered on file save for the build target (project/module) containing the
focused text file.

![Diagnostics](https://user-images.githubusercontent.com/1408093/48774587-f4d5c780-ecca-11e8-8087-acca5a05ca78.png)

### Known limitations

- Slow feedback. Compilation is handled by the build tool, meaning diagnostics
  may take a while to publish for large projects. The batch compilation mode
  used by build tools is slower than the interactive compiler used by IntelliJ
  as you type.

## Goto definition

Navigate to symbol definitions for project sources and Scala/Java library
dependencies.

Symbols are resolved according to the last successful compilation in the build
tool and navigation continues to work despite syntax errors in the open unsaved
buffer.

![Goto Definition](https://user-images.githubusercontent.com/1408093/48776422-1f764f00-ecd0-11e8-96d1-170f2354d50e.gif)

### Known limitations

- Navigation does not work for identifiers that have just been typed in unsaved
  editor buffer. An identifier must compile successfully at least once in order
  to resolve to a definition.
- Navigation does not work for buffers that do not tokenize, for example due to
  unclosed string literals.
- [scalameta/scalameta#1780](https://github.com/scalameta/scalameta/issues/1780)
  extension methods sometimes resolve to the implicit conversion method instead
  of the extension method.
- [scalameta/scalameta#1802](https://github.com/scalameta/scalameta/issues/1802)
  reflective invocations (methods calls on structural types) do not resolve to a
  definition.

## Document symbols

**✅**: Document symbols are displayed in a hierarchical outline.

**Flat**: Document symbols are displayed in a flat outline.

![Document Symbols](https://user-images.githubusercontent.com/1408093/50635569-014c7180-0f53-11e9-8898-62803898781c.gif)

## Formatting

Metals uses Scalafmt to respond to formatting requests from the editor,
according to the configuration defined in `.scalafmt.conf`.

Learn how to configure Scalafmt at
https://scalameta.org/scalafmt/docs/configuration.html.

![Formatting](https://user-images.githubusercontent.com/1408093/50635748-b0894880-0f53-11e9-913b-acfd5f505351.gif)

## Metals Extensions

**Status bar**: Editor client implements the `metals/status` notification.

**Did focus**: Editor client implements the `metals/didFocus` notification.

**Slow task**: Editor client implements the `metals/slowTask` request.

**Input box**: Editor client implements the `metals/inputBox` request.

**✅**: Editor implements all Metals extension endpoints.

The Metals language server supports custom extensions that are not part of the
Language Server Protocol (LSP). These extensions are not necessary for Metals to
function but they improve the user experience. To learn more about Metals
extensions, see [integrating a new editor](new-editor.md).

## Unsupported features

Metals does not support the following features:

- Completions
- Show type of expression
- Show symbol docstring
- Rename symbol
- Remove unused imports
- Refactoring with Scalafix
