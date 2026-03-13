package tests.p

class ProtoPCReferencesSuite extends BaseProtoPCSuite("proto-pc-references") {

  test("type-reference-same-file") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |message Address {
           |  string street = 1;
           |}
           |message Person {
           |  Address address = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      references <- server.references(
        "a/src/main/proto/example.proto",
        "Address",
      )
      _ = assertNoDiff(
        references,
        """|a/src/main/proto/example.proto:2:9: info: reference
           |message Address {
           |        ^^^^^^^
           |a/src/main/proto/example.proto:6:3: info: reference
           |  Address address = 1;
           |  ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-imported-type") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/common.proto
           |syntax = "proto3";
           |message SharedMessage {
           |  string value = 1;
           |}
           |/a/src/main/proto/user.proto
           |syntax = "proto3";
           |import "common.proto";
           |message User {
           |  SharedMessage data = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/user.proto")
      references <- server.references(
        "a/src/main/proto/common.proto",
        "SharedMessage",
      )
      _ = assertNoDiff(
        references,
        """|a/src/main/proto/common.proto:2:9: info: reference
           |message SharedMessage {
           |        ^^^^^^^^^^^^^
           |a/src/main/proto/user.proto:4:3: info: reference
           |  SharedMessage data = 1;
           |  ^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-imported-type-with-option-block") {
    // Regression: ProtoMtagsV1 could stop indexing a message after encountering
    // `}` inside an option block, which dropped later field type references from
    // bloom-filter candidates and made find-references miss valid proto usages.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/common.proto
           |syntax = "proto2";
           |package demo.common;
           |message SharedSettings {
           |  optional string shared_key_id = 1;
           |}
           |/a/src/main/proto/service_a.proto
           |syntax = "proto2";
           |package demo.common;
           |import "common.proto";
           |message ServiceAInfo {
           |  option (custom.message_options) = {
           |    note: "keep-scanning"
           |  };
           |  optional SharedSettings settings = 1;
           |}
           |/a/src/main/proto/service_b.proto
           |syntax = "proto2";
           |package demo.module;
           |import "common.proto";
           |message ServiceBInfo {
           |  option (custom.node_type) = {
           |    id: 1
           |  };
           |  optional demo.common.SharedSettings settings = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/common.proto")
      _ <- server.didOpen("a/src/main/proto/service_a.proto")
      _ <- server.didOpen("a/src/main/proto/service_b.proto")
      refs <- server.references(
        "a/src/main/proto/common.proto",
        "message SharedSettings",
      )
      _ = assertNoDiff(
        refs,
        """|a/src/main/proto/common.proto:3:9: info: reference
           |message SharedSettings {
           |        ^^^^^^^^^^^^^^
           |a/src/main/proto/service_a.proto:8:12: info: reference
           |  optional SharedSettings settings = 1;
           |           ^^^^^^^^^^^^^^
           |a/src/main/proto/service_b.proto:8:24: info: reference
           |  optional demo.common.SharedSettings settings = 1;
           |                       ^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-package-same-name-no-false-positive") {
    // Regression test: find-references should NOT match messages with the same
    // simple name but different fully-qualified names (different packages)
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/openapi.proto
           |syntax = "proto3";
           |package databricks.openapi;
           |message Resource {
           |  string id = 1;
           |}
           |message OpenApiRequest {
           |  Resource resource = 1;
           |}
           |/a/src/main/proto/search.proto
           |syntax = "proto3";
           |package databricks.search;
           |message Resource {
           |  string name = 1;
           |}
           |message ListResourcesResponse {
           |  repeated Resource resources = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/openapi.proto")
      _ <- server.didOpen("a/src/main/proto/search.proto")
      // Find references on databricks.openapi.Resource should NOT include
      // usages of databricks.search.Resource
      openapiRefs <- server.references(
        "a/src/main/proto/openapi.proto",
        "message Resource",
      )
      _ = assertNoDiff(
        openapiRefs,
        """|a/src/main/proto/openapi.proto:3:9: info: reference
           |message Resource {
           |        ^^^^^^^^
           |a/src/main/proto/openapi.proto:7:3: info: reference
           |  Resource resource = 1;
           |  ^^^^^^^^
           |""".stripMargin,
      )
      // Find references on databricks.search.Resource should NOT include
      // usages of databricks.openapi.Resource
      searchRefs <- server.references(
        "a/src/main/proto/search.proto",
        "message Resource",
      )
      _ = assertNoDiff(
        searchRefs,
        """|a/src/main/proto/search.proto:3:9: info: reference
           |message Resource {
           |        ^^^^^^^^
           |a/src/main/proto/search.proto:7:12: info: reference
           |  repeated Resource resources = 1;
           |           ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("rpc-type-references") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/messages.proto
           |syntax = "proto3";
           |message GetUserRequest {
           |  string user_id = 1;
           |}
           |message GetUserResponse {
           |  string name = 1;
           |}
           |/a/src/main/proto/service.proto
           |syntax = "proto3";
           |import "messages.proto";
           |service UserService {
           |  rpc GetUser(GetUserRequest) returns (GetUserResponse);
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/service.proto")
      requestRefs <- server.references(
        "a/src/main/proto/messages.proto",
        "GetUserRequest",
      )
      _ = assertNoDiff(
        requestRefs,
        """|a/src/main/proto/messages.proto:2:9: info: reference
           |message GetUserRequest {
           |        ^^^^^^^^^^^^^^
           |a/src/main/proto/service.proto:4:15: info: reference
           |  rpc GetUser(GetUserRequest) returns (GetUserResponse);
           |              ^^^^^^^^^^^^^^
           |""".stripMargin,
      )
      responseRefs <- server.references(
        "a/src/main/proto/messages.proto",
        "GetUserResponse",
      )
      _ = assertNoDiff(
        responseRefs,
        """|a/src/main/proto/service.proto:4:40: info: reference
           |  rpc GetUser(GetUserRequest) returns (GetUserResponse);
           |                                       ^^^^^^^^^^^^^^^
           |a/src/main/proto/messages.proto:5:9: info: reference
           |message GetUserResponse {
           |        ^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
