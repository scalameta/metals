package completion

import tests.pc.BaseProtoCompletionSuite

class CompletionSuite extends BaseProtoCompletionSuite {

  checkContains(
    "top-level-keywords",
    """
      |syntax = "proto3";
      |@@
      |""".stripMargin,
    List("message", "enum", "service", "import", "option", "package", "extend"),
  )

  checkContains(
    "scalar-types-in-message",
    """
      |syntax = "proto3";
      |message Person {
      |  @@
      |}
      |""".stripMargin,
    List("string", "int32", "int64", "bool", "bytes", "double", "float"),
  )

  checkContains(
    "message-types-in-message",
    """
      |syntax = "proto3";
      |message Address {}
      |message Person {
      |  @@
      |}
      |""".stripMargin,
    List("Address", "Person"),
  )

  checkContains(
    "field-modifiers",
    """
      |syntax = "proto3";
      |message Person {
      |  @@
      |}
      |""".stripMargin,
    List("repeated", "optional"),
  )

  checkContains(
    "enum-types-in-message",
    """
      |syntax = "proto3";
      |enum Status {
      |  UNKNOWN = 0;
      |}
      |message Person {
      |  @@
      |}
      |""".stripMargin,
    List("Status"),
  )

  checkContains(
    "service-keywords",
    """
      |syntax = "proto3";
      |message Request {}
      |message Response {}
      |service Greeter {
      |  @@
      |}
      |""".stripMargin,
    List("rpc", "option"),
  )
}
