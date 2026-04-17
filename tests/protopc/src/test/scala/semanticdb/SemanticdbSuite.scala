package semanticdb

import tests.pc.BaseProtoSemanticdbSuite

class SemanticdbSuite extends BaseProtoSemanticdbSuite {

  check(
    "simple-message",
    """|syntax = "proto3";
       |message Person {
       |  string name = 1;
       |  int32 age = 2;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Person {
       |//         ^^^^^^ definition Person# CLASS
       |     string name = 1;
       |//          ^^^^ definition Person#name. FIELD(val)
       |     int32 age = 2;
       |//         ^^^ definition Person#age. FIELD(val)
       |   }
       |""".stripMargin,
  )

  check(
    "enum",
    """|syntax = "proto3";
       |enum Status {
       |  UNKNOWN = 0;
       |  ACTIVE = 1;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   enum Status {
       |//      ^^^^^^ definition Status# CLASS
       |     UNKNOWN = 0;
       |//   ^^^^^^^ definition Status#UNKNOWN. FIELD(val)
       |     ACTIVE = 1;
       |//   ^^^^^^ definition Status#ACTIVE. FIELD(val)
       |   }
       |""".stripMargin,
  )

  check(
    "message-with-type-ref",
    """|syntax = "proto3";
       |message Address {
       |  string street = 1;
       |}
       |message Person {
       |  Address home = 1;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Address {
       |//         ^^^^^^^ definition Address# CLASS
       |     string street = 1;
       |//          ^^^^^^ definition Address#street. FIELD(val)
       |   }
       |   message Person {
       |//         ^^^^^^ definition Person# CLASS
       |     Address home = 1;
       |//   ^^^^^^^ reference Address#
       |//           ^^^^ definition Person#home. FIELD(val)
       |   }
       |""".stripMargin,
  )

  check(
    "service",
    """|syntax = "proto3";
       |message Request {}
       |message Response {}
       |service Greeter {
       |  rpc SayHello(Request) returns (Response);
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Request {}
       |//         ^^^^^^^ definition Request# CLASS
       |   message Response {}
       |//         ^^^^^^^^ definition Response# CLASS
       |   service Greeter {
       |//         ^^^^^^^ definition Greeter# INTERFACE
       |     rpc SayHello(Request) returns (Response);
       |//       ^^^^^^^^ definition Greeter#SayHello(). METHOD
       |//                ^^^^^^^ reference Request#
       |//                                  ^^^^^^^^ reference Response#
       |   }
       |""".stripMargin,
  )

  check(
    "nested-message",
    """|syntax = "proto3";
       |message Outer {
       |  message Inner {
       |    string value = 1;
       |  }
       |  Inner inner = 1;
       |}
       |""".stripMargin,
    """|   syntax = "proto3";
       |   message Outer {
       |//         ^^^^^ definition Outer# CLASS
       |     message Inner {
       |//           ^^^^^ definition Outer#Inner# CLASS
       |       string value = 1;
       |//            ^^^^^ definition Outer#Inner#value. FIELD(val)
       |     }
       |     Inner inner = 1;
       |//   ^^^^^ reference Outer#Inner#
       |//         ^^^^^ definition Outer#inner. FIELD(val)
       |   }
       |""".stripMargin,
  )
}
