package hover

import tests.pc.BaseProtoHoverSuite

class HoverMessageSuite extends BaseProtoHoverSuite {

  check(
    "message-hover",
    """
      |syntax = "proto3";
      |message Per@@son {
      |  string name = 1;
      |}
      |""".stripMargin,
    """|```protobuf
       |message Person
       |```
       |""".stripMargin,
  )

  check(
    "field-hover",
    """
      |syntax = "proto3";
      |message Person {
      |  string na@@me = 1;
      |}
      |""".stripMargin,
    """|```protobuf
       |string name = 1
       |```
       |""".stripMargin,
  )

  check(
    "enum-hover",
    """
      |syntax = "proto3";
      |enum Sta@@tus {
      |  UNKNOWN = 0;
      |  ACTIVE = 1;
      |}
      |""".stripMargin,
    """|```protobuf
       |enum Status
       |```
       |""".stripMargin,
  )

  check(
    "enum-value-hover",
    """
      |syntax = "proto3";
      |enum Status {
      |  UNK@@NOWN = 0;
      |  ACTIVE = 1;
      |}
      |""".stripMargin,
    """|```protobuf
       |UNKNOWN = 0
       |```
       |""".stripMargin,
  )

  check(
    "service-hover",
    """
      |syntax = "proto3";
      |message Request {}
      |message Response {}
      |service Gre@@eter {
      |  rpc SayHello(Request) returns (Response);
      |}
      |""".stripMargin,
    """|```protobuf
       |service Greeter
       |```
       |""".stripMargin,
  )

  check(
    "rpc-hover",
    """
      |syntax = "proto3";
      |message Request {}
      |message Response {}
      |service Greeter {
      |  rpc Say@@Hello(Request) returns (Response);
      |}
      |""".stripMargin,
    """|```protobuf
       |rpc SayHello(Request) returns (Response)
       |```
       |""".stripMargin,
  )

  check(
    "repeated-field-hover",
    """
      |syntax = "proto3";
      |message Person {
      |  repeated string ta@@gs = 1;
      |}
      |""".stripMargin,
    """|```protobuf
       |repeated string tags = 1
       |```
       |""".stripMargin,
  )
}
