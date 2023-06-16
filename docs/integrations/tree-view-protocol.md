---
id: tree-view-protocol
title: Tree View Protocol
---

Metals implements a Language Server Protocol extension called the "Tree View
Protocol" to render tree views in the editor client.

## Base data structures

### Tree View

One "tree view" represents the root of a tree along with all of its descendent
tree nodes. Multiple tree views can be displayed at the same time in an editor
client.

![Example tree views](https://i.imgur.com/FRWL3Aq.png)

A tree view is uniquely identified by a `viewId: string` field in other data
structures.

### Tree View Node

A tree view can contain multiple tree view nodes. A tree view node can have the
following metadata.

```ts
interface TreeViewNode {
  /** The ID of the view that this node is associated with. */
  viewId: string;
  /** The URI of this node, or undefined if node is a root of the tree. */
  nodeUri?: string;
  /** The title to display for this node. */
  label: string;
  /** An optional command to trigger when the user clicks on node. */
  command?: Command;
  /** An optional SVG icon to display next to the label of this node. */
  icon?: string;
  /** An optional description of this node that is displayed when the user hovers over this node. */
  tooltip?: string;
  /**
   * Whether this tree node should be collapsed, expanded or if it has no children.
   *
   * - undefined: this node has no children.
   * - collapsed: this node has children and this node should be auto-expanded
   *   on the first load.
   * - expanded: this node has children and the user should manually expand
   *   this node to see the children.
   */
  collapseState?: "expanded" | "collapsed";
}
```

The children of a tree view node can be obtained through the
`metals/treeViewChildren` request.

### Tree View Command

```ts
/**
 * A command to execute on the client when the user clicks on a tree view node.
 */
interface TreeViewCommand {
  /** The title of the command, the client is free to not display this title in the UI. */
  title: string;
  /** The identifier of the command that should be executed by the client. */
  command: string;
  /** A description of what this command does. */
  tooltip?: string;
  /** Optional arguments to invoke the command with. */
  arguments?: any[];
}
```

## Endpoints

The Tree View Protocol (TVP) consists of several JSON-RPC requests and
notification endpoints.

### `initialize`

The Tree View Protocol is only enabled when both the client and server declare
support for the protocol by adding an `treeViewProvider: true` field to the
experimental section of the server and client capabilities in the `initialize`
response.

```json
{
  "capabilities": {
    "experimental": {
      "treeViewProvider": true
    }
  }
}
```

### `metals/treeViewChildren`

The tree view children request is sent from the client to the server to get the
children nodes of a tree view node. The client is safe to cache the response of
the children until server sends a `metals/treeViewDidChange` notification for
the parent node or one of its ancestor nodes.

_Request_:

- method: `metals/treeViewChildren`
- params: `TreeViewChildrenParams` defined as follows.

```ts
interface TreeViewChildrenParams {
  /** The ID of the view that this node is associated with. */
  viewId: string;
  /** The URI of the parent node or undefined when listing the root node. */
  nodeUri?: string;
}
```

_Response_:

- result: `TreeViewChildrenResult` defined as follows.

```ts
interface TreeViewChildrenResult {
  /** The child nodes of the requested parent node. */
  nodes: TreeViewNode[];
}
```

### `metals/treeViewParent`

The tree view parent request is sent from the client to the server to obtain the
parent node of a child node. The `metals/treeViewParent` endpoint is required to
support `metals/treeViewReveal`.

_Request_:

- method: `metals/treeViewParent`
- params: `TreeViewParentParams` defined as follows.

```ts
interface TreeViewParentParams {
  /** The ID of the view that the nodeUri is associated with. */
  viewId: string;
  /** The URI of the child node. */
  nodeUri: string;
}
```

_Response_:

- result: `TreeViewParentResult` defined as follows.

```ts
interface TreeViewParentResult {
  /** The parent node URI or undefined when the parent is the root node. */
  uri?: string;
}
```

### `metals/treeViewDidChange`

The tree view did change notification is sent from the server to the client to
notify that the metadata about a given tree view node has changed.

_Notification_:

- method: `metals/treeViewVisibilityDidChange`
- params: `TreeViewDidChangeParams` defined as follows:

```ts
interface TreeViewDidChangeParams {
  /** The nodes that have changed. */
  nodes: TreeViewNode[];
}
```

### `metals/treeViewVisibilityDidChange`

The visibility did change notification is sent from the client to the server to
notify that the visibility of a tree view has changed.

_Notification_:

- method: `metals/treeViewVisibilityDidChange`
- params: `TreeViewVisibilityDidChangeParams` defined as follows:

```ts
interface TreeViewVisibilityDidChangeParams {
  /** The ID of the view that this node is associated with. */
  viewId: string;
  /** True if the node is visible in the editor UI, false otherwise. */
  visible: boolean;
}
```

### `metals/treeViewNodeCollapseDidChange`

The collapse did change notification is sent from the client to the server to
notify that a tree node has either been collapsed or expanded.

_Notification_:

- method: `metals/treeViewNodeCollapseDidChange`
- params: `TreeViewNodeCollapseDidChangeParams` defined as follows:

```ts
interface TreeViewNodeCollapseDidChangeParams {
  /** The ID of the view that this node is associated with. */
  viewId: string;
  /** The URI of the node that was collapsed or expanded. */
  nodeUri: string;
  /** True if the node is collapsed, false if the node was expanded. */
  collapsed: boolean;
}
```

### `metals/treeViewReveal`

The reveal request is sent from the client to the server to convert a text
document position into it's corresponding tree view node.

_Request_:

- method: `metals/treeViewReveal`
- params: `TextDocumentPositionParams`, as defined in LSP.

_Response_:

- result: `TreeViewRevealResult` defined as follows.

```ts
interface MetalsTreeRevealResult {
  /** The ID of the view that this node is associated with. */
  viewId: string;
  /**
   * The list of URIs for the node to reveal and all of its ancestor parents.
   *
   * The node to reveal is at index 0, it's parent is at index 1 and so forth
   * up until the root node.
   */
  uriChain: string[];
}
```

Example implementation of the reveal request.

![2019-06-27 18 47 17](https://user-images.githubusercontent.com/1408093/60284529-0d1a5e80-990c-11e9-853a-0aa0f6e12993.gif)
