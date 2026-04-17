package tests.p

class ProtoPCDefinitionSuite extends BaseProtoPCSuite("proto-pc-definition") {

  test("type-reference") {
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
      _ <- server.assertDefinition(
        "a/src/main/proto/example.proto",
        "Add@@ress address = 1;",
        """|a/src/main/proto/example.proto:2:9: definition
           |message Address {
           |        ^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("nested-message-reference") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |message Outer {
           |  message Inner {
           |    string value = 1;
           |  }
           |  Inner inner = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      _ <- server.assertDefinition(
        "a/src/main/proto/example.proto",
        "Inn@@er inner = 1;",
        """|a/src/main/proto/example.proto:3:11: definition
           |  message Inner {
           |          ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("nested-enum-reference") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |message Container {
           |  enum Status {
           |    UNKNOWN = 0;
           |    ACTIVE = 1;
           |  }
           |  Status status = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      _ <- server.assertDefinition(
        "a/src/main/proto/example.proto",
        "Sta@@tus status = 1;",
        """|a/src/main/proto/example.proto:3:8: definition
           |  enum Status {
           |       ^^^^^^
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
      _ <- server.assertDefinition(
        "a/src/main/proto/user.proto",
        "Shared@@Message data = 1;",
        """|a/src/main/proto/common.proto:2:9: definition
           |message SharedMessage {
           |        ^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("import-string-goto-definition") {
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
      _ <- server.assertDefinition(
        "a/src/main/proto/user.proto",
        "import \"common.p@@roto\";",
        """|a/src/main/proto/common.proto:1:1: definition
           |syntax = "proto3";
           |^
           |""".stripMargin,
      )
    } yield ()
  }

  test("well-known-import-goto-definition-uses-jar-uri") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/event.proto
           |syntax = "proto3";
           |import "google/protobuf/timestamp.proto";
           |message Event {
           |  google.protobuf.Timestamp created_at = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/event.proto")
      _ <- server.assertDefinition(
        "a/src/main/proto/event.proto",
        "import \"google/protobuf/timest@@amp.proto\";",
        """|protobuf-java-4.31.1.jar!/google/protobuf/timestamp.proto:1:1: definition
           |// Protocol Buffers - Google's data interchange format
           |^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-fully-qualified-type") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/types.proto
           |syntax = "proto3";
           |package my.pkg;
           |message SymbolInfo {
           |  enum Kind {
           |    UNKNOWN = 0;
           |    CLASS = 1;
           |  }
           |}
           |/a/src/main/proto/index.proto
           |syntax = "proto3";
           |package my.other;
           |import "types.proto";
           |message Index {
           |  my.pkg.SymbolInfo.Kind kind = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/index.proto")
      _ <- server.assertDefinition(
        "a/src/main/proto/index.proto",
        "my.pkg.SymbolInfo.Ki@@nd kind = 1;",
        """|a/src/main/proto/types.proto:4:8: definition
           |  enum Kind {
           |       ^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-file-nested-message-type") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/types.proto
           |syntax = "proto3";
           |package types;
           |message Outer {
           |  message Inner {
           |    string value = 1;
           |  }
           |}
           |/a/src/main/proto/user.proto
           |syntax = "proto3";
           |import "types.proto";
           |message User {
           |  types.Outer.Inner data = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/user.proto")
      _ <- server.assertDefinition(
        "a/src/main/proto/user.proto",
        "types.Outer.Inn@@er data = 1;",
        """|a/src/main/proto/types.proto:4:11: definition
           |  message Inner {
           |          ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("cross-project-qualified-type-definition") {
    // Tests goto-definition for qualified type references across sibling projects.
    // This mimics the user's scenario with cluster.ClusterTag from a different project.
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
      _ <- server.assertDefinition(
        "cmv2-frontend/api/proto/rpc_data_model.proto",
        "cluster.Cluster@@Tag tag = 2;",
        """|cluster-common/api/messages/common.proto:3:9: definition
           |message ClusterTag {
           |        ^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
