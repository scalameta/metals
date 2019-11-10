---
id: decoration-protocol
sidebar_label: Decoration Protocol
title: Decoration Protocol v0.1.0
---

Metals implements a Language Server Protocol extension called the "Decoration
Protocol" to display non-editable text in the text editor.

## Base data structures

The Decoration Protocol has several base data structures that are mostly derived
from the [VS Code API](https://code.visualstudio.com/api/references/vscode-api).

### Decoration

A "decoration" represents non-editable code that is display in the text editor
alongside editable code. The GIF below demonstrates an example of green
decorations that are formatted as comments, which contain the evaluated code.

![Example decoration](https://user-images.githubusercontent.com/1408093/68091453-bacbea00-fe77-11e9-80b9-52a9bbd6d98a.gif)

Although decorations appear as text inside the editor, they can't be edited by
the user. In the GIF above, observe that the decoration can optionally include a
message that's displayed on mouse hover.

## DecorationOptions

```ts
export interface DecorationOptions {
  /**
   * The range position to place the decoration.
   * The Range data structure is defined in the Language Server Protocol.
   */
  range: Range;
  /**
   * The text to display when the mouse hovers over the decoration.
   * The MarkedString data structure is defined in the LanguageServerProtocol
   */
  hoverMessage?: MarkedString;
  /** The URI of the text document to place text decorations */
  renderOptions: ThemableDecorationInstanceRenderOption;
}
```

## ThemableDecorationInstanceRenderOption

```ts
export interface ThemableDecorationInstanceRenderOption {
  /** The decoration to display next to the given range. */
  after?: ThemableDecorationAttachmentRenderOptions;
}
```

## ThemableDecorationAttachmentRenderOptions

```ts
export interface ThemableDecorationAttachmentRenderOptions {
  /** The text to display in the decoration */
  contentText?: string;
  /** The color of `contentText`. More colors may be added in the future. */
  color?: "green";
  /** The font style to use for displaying `contentText. More styles may be added in the future.  */
  fontStyle?: "italic";
}
```

## Endpoints

The Decoration Protocol is embedded inside the Language Server Protocol and
consists of a single JSON-RPC notification.

### `initialize`

The Decoration Protocol is only enabled when both the client and server declare
support for the protocol by adding an `decorationProvider: true` field to the
experimental section of the server and client capabilities in the `initialize`
response.

```json
{
  "capabilities": {
    "experimental": {
      "decorationProvider": true
    }
  }
}
```

### `metals/publishDecorations`

The decoration ranges did change notification is sent from the server to the
client to notify that decorations have changes for a given text document.

_Notification_:

- method: `metals/publishDecorations`
- params: `PublishDecorationsParams` as defined below:

```ts
export interface PublishDecorationsParams {
  /** The URI of the text document to place text decorations */
  uri: string;

  /**
   * The ranges to publish for this given document.
   * Use empty list to clear all decorations.
   */
  options: DecorationOptions[];
}
```

## Changelog

- v0.1.0: First release with basic support for worksheets.
