package tests.p

class ProtoPCDiagnosticsSuite extends BaseProtoPCSuite("proto-pc-diagnostics") {

  test("no-errors") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |message Person {
           |  string name = 1;
           |  int32 age = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      _ <- server.didFocus("a/src/main/proto/example.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("syntax-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/invalid.proto
           |syntax = "proto3"
           |message Person {
           |  string name = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/invalid.proto")
      _ <- server.didFocus("a/src/main/proto/invalid.proto")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/proto/invalid.proto:2:1: error: Expected ';', got: message
           |message Person {
           |^
           |""".stripMargin,
      )
    } yield ()
  }

  test("unknown-type-error") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/unknown_type.proto
           |syntax = "proto3";
           |message Person {
           |  UnknownType field = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/unknown_type.proto")
      _ <- server.didFocus("a/src/main/proto/unknown_type.proto")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/proto/unknown_type.proto:3:3: error: Unknown type: UnknownType
           |  UnknownType field = 1;
           |  ^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-import") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/common.proto
           |syntax = "proto3";
           |package common;
           |message Timestamp {
           |  int64 seconds = 1;
           |  int32 nanos = 2;
           |}
           |/a/src/main/proto/main.proto
           |syntax = "proto3";
           |import "common.proto";
           |message Event {
           |  string name = 1;
           |  common.Timestamp created_at = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/common.proto")
      _ <- server.didOpen("a/src/main/proto/main.proto")
      _ <- server.didFocus("a/src/main/proto/main.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-file-import-missing") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/main.proto
           |syntax = "proto3";
           |import "common.proto";
           |message Event {
           |  string name = 1;
           |  common.Timestamp created_at = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/main.proto")
      _ <- server.didFocus("a/src/main/proto/main.proto")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/proto/main.proto:5:3: error: Unknown type: common.Timestamp
           |  common.Timestamp created_at = 2;
           |  ^^^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-import-subdirectory") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/types/timestamp.proto
           |syntax = "proto3";
           |package types;
           |message Timestamp {
           |  int64 seconds = 1;
           |  int32 nanos = 2;
           |}
           |/a/src/main/proto/events/event.proto
           |syntax = "proto3";
           |import "types/timestamp.proto";
           |package events;
           |message Event {
           |  string name = 1;
           |  types.Timestamp created_at = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/types/timestamp.proto")
      _ <- server.didOpen("a/src/main/proto/events/event.proto")
      _ <- server.didFocus("a/src/main/proto/events/event.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-file-import-nested-message") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/outer.proto
           |syntax = "proto3";
           |package outer;
           |message Outer {
           |  message Inner {
           |    string value = 1;
           |  }
           |}
           |/a/src/main/proto/consumer.proto
           |syntax = "proto3";
           |import "outer.proto";
           |message Consumer {
           |  outer.Outer.Inner nested = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/outer.proto")
      _ <- server.didOpen("a/src/main/proto/consumer.proto")
      _ <- server.didFocus("a/src/main/proto/consumer.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-file-import-deeply-nested-dirs") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/com/example/models/user.proto
           |syntax = "proto3";
           |package com.example.models;
           |message User {
           |  string id = 1;
           |  string name = 2;
           |}
           |/a/src/main/proto/com/example/api/v1/service.proto
           |syntax = "proto3";
           |import "com/example/models/user.proto";
           |package com.example.api.v1;
           |message GetUserRequest {
           |  string user_id = 1;
           |}
           |message GetUserResponse {
           |  com.example.models.User user = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/com/example/models/user.proto")
      _ <- server.didOpen("a/src/main/proto/com/example/api/v1/service.proto")
      _ <- server.didFocus("a/src/main/proto/com/example/api/v1/service.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-file-import-multiple-imports") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/types/timestamp.proto
           |syntax = "proto3";
           |package types;
           |message Timestamp {
           |  int64 seconds = 1;
           |}
           |/a/src/main/proto/types/duration.proto
           |syntax = "proto3";
           |package types;
           |message Duration {
           |  int64 seconds = 1;
           |}
           |/a/src/main/proto/combined.proto
           |syntax = "proto3";
           |import "types/timestamp.proto";
           |import "types/duration.proto";
           |message TimeRange {
           |  types.Timestamp start = 1;
           |  types.Duration length = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/types/timestamp.proto")
      _ <- server.didOpen("a/src/main/proto/types/duration.proto")
      _ <- server.didOpen("a/src/main/proto/combined.proto")
      _ <- server.didFocus("a/src/main/proto/combined.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("parent-package-resolution") {
    // Tests that types from sibling packages under a common parent can be
    // resolved using a relative package prefix.
    // For example: from package "acme.admin", a reference to "api.Request"
    // should resolve to "acme.api.Request" (parent package + relative).
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/acme/api/contract.proto
           |syntax = "proto2";
           |package acme.api;
           |message GetContractRequest {
           |  optional string contract_id = 1;
           |}
           |message Contract {
           |  optional string id = 1;
           |}
           |/a/src/main/proto/acme/admin/service.proto
           |syntax = "proto2";
           |package acme.admin;
           |import "acme/api/contract.proto";
           |service AdminService {
           |  rpc GetContract(api.GetContractRequest) returns (api.Contract);
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/acme/api/contract.proto")
      _ <- server.didOpen("a/src/main/proto/acme/admin/service.proto")
      _ <- server.didFocus("a/src/main/proto/acme/admin/service.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("parent-package-resolution-multiple-services") {
    // Tests resolution of types from multiple sibling packages.
    // From package "acme.admin", references like "credits.ListRequest"
    // should resolve to "acme.credits.ListRequest".
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/acme/api/contract.proto
           |syntax = "proto2";
           |package acme.api;
           |message GetContractRequest {
           |  optional string contract_id = 1;
           |}
           |message Contract {
           |  optional string id = 1;
           |}
           |/a/src/main/proto/acme/credits/service.proto
           |syntax = "proto2";
           |package acme.credits;
           |message ListCreditsRequest {
           |  optional string subscription_id = 1;
           |}
           |message ListCreditsResponse {
           |  optional string result = 1;
           |}
           |/a/src/main/proto/acme/admin/service.proto
           |syntax = "proto2";
           |package acme.admin;
           |import "acme/api/contract.proto";
           |import "acme/credits/service.proto";
           |service AdminService {
           |  rpc GetContract(api.GetContractRequest) returns (api.Contract);
           |  rpc ListCredits(credits.ListCreditsRequest) returns (credits.ListCreditsResponse);
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/acme/api/contract.proto")
      _ <- server.didOpen("a/src/main/proto/acme/credits/service.proto")
      _ <- server.didOpen("a/src/main/proto/acme/admin/service.proto")
      _ <- server.didFocus("a/src/main/proto/acme/admin/service.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-project-import") {
    // Tests imports between sibling top-level directories (different projects).
    // This mimics a monorepo setup where:
    //   project-a/api/proto/main.proto imports from
    //   project-b/api/messages/common.proto
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/cluster-common/api/messages/common.proto
           |syntax = "proto3";
           |package cluster;
           |message ClusterTag {
           |  string name = 1;
           |  string value = 2;
           |}
           |/cmv2-frontend/api/proto/rpc_data_model.proto
           |syntax = "proto3";
           |import "cluster-common/api/messages/common.proto";
           |package rpc;
           |message DataModel {
           |  string id = 1;
           |  cluster.ClusterTag tag = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("cluster-common/api/messages/common.proto")
      _ <- server.didOpen("cmv2-frontend/api/proto/rpc_data_model.proto")
      _ <- server.didFocus("cmv2-frontend/api/proto/rpc_data_model.proto")
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("cross-project-import-short-path") {
    // Tests imports using short path format (just filename or partial path).
    // This matches how some monorepos configure proto imports with explicit
    // proto roots that make shorter import paths possible.
    // The ImportResolver searches sibling directories at each level for matching paths.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/cluster-common/api/messages/common.proto
           |syntax = "proto3";
           |package cluster;
           |message ClusterTag {
           |  string name = 1;
           |  string value = 2;
           |}
           |/cmv2-frontend/api/proto/rpc_data_model.proto
           |syntax = "proto3";
           |import "api/messages/common.proto";
           |package rpc;
           |message DataModel {
           |  string id = 1;
           |  cluster.ClusterTag tag = 2;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("cluster-common/api/messages/common.proto")
      _ <- server.didOpen("cmv2-frontend/api/proto/rpc_data_model.proto")
      _ <- server.didFocus("cmv2-frontend/api/proto/rpc_data_model.proto")
      // With the enhanced import resolution, partial paths are resolved
      // by searching sibling directories at each parent level
      _ = assertNoDiagnostics()
    } yield ()
  }
}
