---
id: remote-language-server
title: Remote Language Servers
---

Metals has experimental support to offload certain requests to a remote language
server. This feature can be used to navigate large codebases when its not
possible to index all the code on a local computer.

## Difference from local language servers

There are some important differences between local and remote language servers:

- Instead of JSON-RPC, a remote language server responds to HTTP POST requests
  with an `application/json` header and a JSON-formatted body.
- Instead of using absolute `file://` URIs, a remote language server uses
  relative `source://` URIs. For example, the absolute URI
  `file://path/to/workspace/src/main/scala/Address.scala` becomes the relative
  URI `source://src/main/scala/Address.scala` when communicating with a remote
  language server.

## Methods

Each remote language server method expects a JSON-formatted body of type
`JsonRpcRequest<T>`.

```ts
interface JsonRpcRequest<T> {
  /** The JSON-RPC method name, for example textDocument/definition */
  method: string;

  /** The parameter for the JSON-RPC method, for example `TextDocumentPositionParams` */
  params: T;

  /** The ID of this request, can be any integer number. */
  id: number;
}
```

### `textDocument/definition`

The `textDocument/definition` request is sent from the client to the server to
get the list of definitions for a given position.

_Request_:

- method: `textDocument/definition`
- params: `JsonRpcRequest<TextDocumentPositionParams>`, where
  `TextDocumentPositionParams` is defined in LSP.

_Response_:

- result: `Location[]`, as defined in LSP.

_Example request_:

```sh
curl --location --request POST 'http://remote-language-server.com' \
--header 'Content-Type: application/json' \
--data-raw '{
  "method": "textDocument/definition",
  "params": {
    "textDocument": {
      "uri": "source://src/main/scala/Address.scala"
    },
    "position": {
      "line": 5,
      "character": 10
    }
  },
  "id": 10
}'
```

_Example response_:

```json
[
  {
    "uri": "source://src/main/scala/User.scala",
    "range": {
      "start": { "line": 61, "character": 15 },
      "end": { "line": 61, "character": 31 }
    }
  }
]
```

### `textDocument/references`

The `textDocument/references` request is sent from the client to the server to
get the list of all references to a symbol at a given position.

_Request_:

- method: `textDocument/references`
- params: `JsonRpcRequest<ReferenceParams>`, where `ReferenceParams` is defined
  in LSP.

_Response_:

- result: `Location[]`, as defined in LSP.

_Example request_:

```sh
curl --location --request POST 'http://remote-language-server.com' \
--header 'Content-Type: application/json' \
--data-raw '{
  "method": "textDocument/references",
  "params": {
    "textDocument": {
      "uri": "source://src/main/scala/Address.scala"
    },
    "position": {
      "line": 5,
      "character": 10
    },
    "context": {
      "includeDeclaration": true
    }
  },
  "id": 10
}'
```

_Example response_:

```json
[
  {
    "uri": "source://src/main/scala/User.scala",
    "range": {
      "start": { "line": 61, "character": 15 },
      "end": { "line": 61, "character": 31 }
    }
  },
  {
    "uri": "source://src/main/scala/Country.scala",
    "range": {
      "start": { "line": 62, "character": 16 },
      "end": { "line": 62, "character": 32 }
    }
  }
]
```
