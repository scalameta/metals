package semantictokens

import tests.pc.BaseProtoSemanticTokensSuite

class SemanticTokensSuite extends BaseProtoSemanticTokensSuite {

  checkNodes(
    "message-class",
    """|syntax = "proto3";
       |message Person {
       |  string name = 1;
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Person>>/*class*/ {
       |  string <<name>>/*property*/ = 1;
       |}
       |""".stripMargin,
  )

  checkNodes(
    "message-with-type-ref",
    """|syntax = "proto3";
       |message Address {
       |  string street = 1;
       |}
       |message Person {
       |  string name = 1;
       |  Address address = 2;
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Address>>/*class*/ {
       |  string <<street>>/*property*/ = 1;
       |}
       |message <<Person>>/*class*/ {
       |  string <<name>>/*property*/ = 1;
       |  <<Address>>/*type*/ <<address>>/*property*/ = 2;
       |}
       |""".stripMargin,
  )

  checkNodes(
    "enum",
    """|syntax = "proto3";
       |enum Status {
       |  UNKNOWN = 0;
       |  ACTIVE = 1;
       |  INACTIVE = 2;
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |enum <<Status>>/*enum*/ {
       |  <<UNKNOWN>>/*enumMember*/ = 0;
       |  <<ACTIVE>>/*enumMember*/ = 1;
       |  <<INACTIVE>>/*enumMember*/ = 2;
       |}
       |""".stripMargin,
  )

  checkNodes(
    "service",
    """|syntax = "proto3";
       |message Request {}
       |message Response {}
       |service Greeter {
       |  rpc SayHello(Request) returns (Response);
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Request>>/*class*/ {}
       |message <<Response>>/*class*/ {}
       |service <<Greeter>>/*interface*/ {
       |  rpc <<SayHello>>/*method*/(<<Request>>/*type*/) returns (<<Response>>/*type*/);
       |}
       |""".stripMargin,
  )

  checkNodes(
    "nested-message",
    """|syntax = "proto3";
       |message Outer {
       |  message Inner {
       |    string value = 1;
       |  }
       |  Inner inner = 1;
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Outer>>/*class*/ {
       |  message <<Inner>>/*class*/ {
       |    string <<value>>/*property*/ = 1;
       |  }
       |  <<Inner>>/*type*/ <<inner>>/*property*/ = 1;
       |}
       |""".stripMargin,
  )

  checkNodes(
    "oneof",
    """|syntax = "proto3";
       |message Sample {
       |  oneof choice {
       |    string text = 1;
       |    int32 number = 2;
       |  }
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Sample>>/*class*/ {
       |  oneof <<choice>>/*property*/ {
       |    string <<text>>/*property*/ = 1;
       |    int32 <<number>>/*property*/ = 2;
       |  }
       |}
       |""".stripMargin,
  )

  checkNodes(
    "map-field",
    """|syntax = "proto3";
       |message Labels {
       |  map<string, string> labels = 1;
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Labels>>/*class*/ {
       |  map<string, string> <<labels>>/*property*/ = 1;
       |}
       |""".stripMargin,
  )

  checkNodes(
    "map-field-with-message-value",
    """|syntax = "proto3";
       |message Value {
       |  string data = 1;
       |}
       |message Container {
       |  map<string, Value> items = 1;
       |}
       |""".stripMargin,
    """|syntax = "proto3";
       |message <<Value>>/*class*/ {
       |  string <<data>>/*property*/ = 1;
       |}
       |message <<Container>>/*class*/ {
       |  map<string, <<Value>>/*type*/> <<items>>/*property*/ = 1;
       |}
       |""".stripMargin,
  )
}
