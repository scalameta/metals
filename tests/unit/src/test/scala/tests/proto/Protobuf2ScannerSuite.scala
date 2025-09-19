package tests.proto

class Protobuf2ScannerSuite extends BaseProtobufScannerSuite {

  check(
    "proto2-groups",
    """
     message SearchRequest {
       required string query = 1;
       group Result = 2 {
         required string url = 3;
         optional string title = 4;
       }
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       SearchRequest
       |LEFT_BRACE       {
       |REQUIRED         required
       |STRING           string
       |IDENTIFIER       query
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |GROUP            group
       |IDENTIFIER       Result
       |EQUALS           =
       |INTEGER          2
       |LEFT_BRACE       {
       |REQUIRED         required
       |STRING           string
       |IDENTIFIER       url
       |EQUALS           =
       |INTEGER          3
       |SEMICOLON        ;
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       title
       |EQUALS           =
       |INTEGER          4
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "proto2-extensions-basic",
    """
     message Foo {
       extensions 100 to 199;
       extensions 1000 to max;
       required string name = 1;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       Foo
       |LEFT_BRACE       {
       |EXTENSIONS       extensions
       |INTEGER          100
       |TO               to
       |INTEGER          199
       |SEMICOLON        ;
       |EXTENSIONS       extensions
       |INTEGER          1000
       |TO               to
       |IDENTIFIER       max
       |SEMICOLON        ;
       |REQUIRED         required
       |STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "proto2-extend-message",
    """
     extend Foo {
       optional int32 bar = 126;
       repeated string tags = 127;
     }
   """,
    """|EXTEND           extend
       |IDENTIFIER       Foo
       |LEFT_BRACE       {
       |OPTIONAL         optional
       |INT32            int32
       |IDENTIFIER       bar
       |EQUALS           =
       |INTEGER          126
       |SEMICOLON        ;
       |REPEATED         repeated
       |STRING           string
       |IDENTIFIER       tags
       |EQUALS           =
       |INTEGER          127
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "proto2-complex-with-all-features",
    """
     syntax = "proto2";
     package com.example.proto2;
     
     message User {
       extensions 100 to 199;
       required string name = 1;
       optional int32 age = 2 [default = 0];
       repeated string roles = 3;
       
       group ContactInfo = 4 {
         required string email = 5;
         optional string phone = 6;
       }
       
       enum Status {
         ACTIVE = 1;
         INACTIVE = 0;
       }
       required Status status = 7 [default = ACTIVE];
     }
     
     extend User {
       optional string nickname = 101;
     }
   """,
    """|SYNTAX           syntax
       |EQUALS           =
       |STRING           "proto2"
       |SEMICOLON        ;
       |PACKAGE          package
       |IDENTIFIER       com
       |DOT              .
       |IDENTIFIER       example
       |DOT              .
       |IDENTIFIER       proto2
       |SEMICOLON        ;
       |MESSAGE          message
       |IDENTIFIER       User
       |LEFT_BRACE       {
       |EXTENSIONS       extensions
       |INTEGER          100
       |TO               to
       |INTEGER          199
       |SEMICOLON        ;
       |REQUIRED         required
       |STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |OPTIONAL         optional
       |INT32            int32
       |IDENTIFIER       age
       |EQUALS           =
       |INTEGER          2
       |LEFT_BRACKET     [
       |DEFAULT          default
       |EQUALS           =
       |INTEGER          0
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |REPEATED         repeated
       |STRING           string
       |IDENTIFIER       roles
       |EQUALS           =
       |INTEGER          3
       |SEMICOLON        ;
       |GROUP            group
       |IDENTIFIER       ContactInfo
       |EQUALS           =
       |INTEGER          4
       |LEFT_BRACE       {
       |REQUIRED         required
       |STRING           string
       |IDENTIFIER       email
       |EQUALS           =
       |INTEGER          5
       |SEMICOLON        ;
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       phone
       |EQUALS           =
       |INTEGER          6
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |ENUM             enum
       |IDENTIFIER       Status
       |LEFT_BRACE       {
       |IDENTIFIER       ACTIVE
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |IDENTIFIER       INACTIVE
       |EQUALS           =
       |INTEGER          0
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |REQUIRED         required
       |IDENTIFIER       Status
       |IDENTIFIER       status
       |EQUALS           =
       |INTEGER          7
       |LEFT_BRACKET     [
       |DEFAULT          default
       |EQUALS           =
       |IDENTIFIER       ACTIVE
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EXTEND           extend
       |IDENTIFIER       User
       |LEFT_BRACE       {
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       nickname
       |EQUALS           =
       |INTEGER          101
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "proto2-mixed-field-types",
    """
     message MixedMessage {
       required double price = 1 [default = 0.0];
       optional fixed64 timestamp = 2;
       repeated sint32 values = 3 [packed = true];
       required bytes data = 4;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       MixedMessage
       |LEFT_BRACE       {
       |REQUIRED         required
       |DOUBLE           double
       |IDENTIFIER       price
       |EQUALS           =
       |INTEGER          1
       |LEFT_BRACKET     [
       |DEFAULT          default
       |EQUALS           =
       |FLOAT            0.0
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |OPTIONAL         optional
       |FIXED64          fixed64
       |IDENTIFIER       timestamp
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |REPEATED         repeated
       |SINT32           sint32
       |IDENTIFIER       values
       |EQUALS           =
       |INTEGER          3
       |LEFT_BRACKET     [
       |IDENTIFIER       packed
       |EQUALS           =
       |TRUE             true
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |REQUIRED         required
       |BYTES            bytes
       |IDENTIFIER       data
       |EQUALS           =
       |INTEGER          4
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )
}
