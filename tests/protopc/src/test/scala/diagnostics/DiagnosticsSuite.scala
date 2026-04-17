package diagnostics

import tests.pc.BaseProtoDiagnosticsSuite

class DiagnosticsSuite extends BaseProtoDiagnosticsSuite {
  checkNoDiagnostics(
    "valid-proto",
    """
      |syntax = "proto3";
      |message Person {
      |  string name = 1;
      |  int32 age = 2;
      |}
      |""".stripMargin,
  )

  checkNoDiagnostics(
    "valid-enum",
    """
      |syntax = "proto3";
      |enum Status {
      |  UNKNOWN = 0;
      |  ACTIVE = 1;
      |}
      |""".stripMargin,
  )

  checkNoDiagnostics(
    "valid-service",
    """
      |syntax = "proto3";
      |message Request {}
      |message Response {}
      |service Greeter {
      |  rpc SayHello(Request) returns (Response);
      |}
      |""".stripMargin,
  )

  // Syntax error tests with range assertions
  check(
    "missing-semicolon",
    """|syntax = "proto3"
       |message Person {
       |  string name = 1;
       |}
       |""".stripMargin,
    """|   syntax = "proto3"
       |   message Person {
       |// ^ diagnostic - error Expected ';', got: message
       |     string name = 1;
       |   }
       |""".stripMargin,
  )

  check(
    "missing-field-number",
    """|syntax = "proto3";
       |message Person {
       |  string name;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Person {
       |     string name;
       |//              ^ diagnostic - error Expected '=', got: ;
       |   }
       |""".stripMargin,
  )

  // Note: diagnostic at EOF doesn't show in SemanticdbPrinter output since there's no line to annotate
  check(
    "missing-closing-brace",
    """|syntax = "proto3";
       |message Person {
       |  string name = 1;
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Person {
       |     string name = 1;
       |""".stripMargin,
  )

  // Name resolution error tests
  check(
    "unknown-field-type",
    """|syntax = "proto3";
       |message Person {
       |  UnknownType field = 1;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Person {
       |     UnknownType field = 1;
       |//   ^^^^^^^^^^^ diagnostic - error Unknown type: UnknownType
       |   }
       |""".stripMargin,
  )

  check(
    "unknown-rpc-input-type",
    """|syntax = "proto3";
       |message Response {}
       |service MyService {
       |  rpc Call(UnknownRequest) returns (Response);
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Response {}
       |   service MyService {
       |     rpc Call(UnknownRequest) returns (Response);
       |//            ^^^^^^^^^^^^^^ diagnostic - error Unknown type: UnknownRequest
       |   }
       |""".stripMargin,
  )

  check(
    "unknown-rpc-output-type",
    """|syntax = "proto3";
       |message Request {}
       |service MyService {
       |  rpc Call(Request) returns (UnknownResponse);
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Request {}
       |   service MyService {
       |     rpc Call(Request) returns (UnknownResponse);
       |//                              ^^^^^^^^^^^^^^^ diagnostic - error Unknown type: UnknownResponse
       |   }
       |""".stripMargin,
  )

  check(
    "unknown-map-value-type",
    """|syntax = "proto3";
       |message Container {
       |  map<string, UnknownValue> items = 1;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Container {
       |     map<string, UnknownValue> items = 1;
       |//               ^^^^^^^^^^^^ diagnostic - error Unknown type: UnknownValue
       |   }
       |""".stripMargin,
  )
}
