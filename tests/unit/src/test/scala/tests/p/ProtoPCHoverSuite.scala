package tests.p

class ProtoPCHoverSuite extends BaseProtoPCSuite("proto-pc-hover") {

  test("message-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |message Person {
           |  string name = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      _ <- server.assertHover(
        "a/src/main/proto/example.proto",
        """|syntax = "proto3";
           |message Pers@@on {
           |  string name = 1;
           |}
           |""".stripMargin,
        """|```protobuf
           |message Person
           |```
           |""".stripMargin,
      )
    } yield ()
  }

  test("field-hover") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/proto/example.proto
           |syntax = "proto3";
           |message Person {
           |  string name = 1;
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/proto/example.proto")
      _ <- server.assertHover(
        "a/src/main/proto/example.proto",
        """|syntax = "proto3";
           |message Person {
           |  string na@@me = 1;
           |}
           |""".stripMargin,
        """|```protobuf
           |string name = 1
           |```
           |""".stripMargin,
      )
    } yield ()
  }
}
