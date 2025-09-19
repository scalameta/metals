package tests.proto

import scala.meta.internal.mtags.proto.ProtobufScanner

import munit.TestOptions

abstract class BaseProtobufScannerSuite extends munit.FunSuite {
  def check(
      name: TestOptions,
      protoFileContent: String,
      expectedTokens: String,
  )(implicit loc: munit.Location): Unit =
    test(name) {
      val scanner = new ProtobufScanner(protoFileContent, "example.proto")
      val tokens = scanner.scanAll()
      assertNoDiff(
        tokens.map(t => f"${t.tpeName}%-15s  ${t.lexeme}").mkString("\n"),
        expectedTokens,
      )
    }

}
class ProtobufScannerSuite extends BaseProtobufScannerSuite {

  check(
    "basic",
    """
    syntax = "proto3"; // Define syntax version
    package com.example;

    /*
     * User service definition
     */
    service UserService {
      rpc GetUser(GetUserRequest) returns (User) {}
    }

    message User {
      optional string name = 1;
      int32 id = 2; // User's unique ID
      float account_balance = 3.14;
    }
  """,
    """|SYNTAX           syntax
       |EQUALS           =
       |STRING           "proto3"
       |SEMICOLON        ;
       |PACKAGE          package
       |IDENTIFIER       com
       |DOT              .
       |IDENTIFIER       example
       |SEMICOLON        ;
       |SERVICE          service
       |IDENTIFIER       UserService
       |LEFT_BRACE       {
       |RPC              rpc
       |IDENTIFIER       GetUser
       |LEFT_PAREN       (
       |IDENTIFIER       GetUserRequest
       |RIGHT_PAREN      )
       |RETURNS          returns
       |LEFT_PAREN       (
       |IDENTIFIER       User
       |RIGHT_PAREN      )
       |LEFT_BRACE       {
       |RIGHT_BRACE      }
       |RIGHT_BRACE      }
       |MESSAGE          message
       |IDENTIFIER       User
       |LEFT_BRACE       {
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |INT32            int32
       |IDENTIFIER       id
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |FLOAT            float
       |IDENTIFIER       account_balance
       |EQUALS           =
       |FLOAT            3.14
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  // Core Token Types (Individual Focus)
  check(
    "empty",
    "",
    "EOF\n",
  )

  check(
    "identifier",
    "hello",
    """|IDENTIFIER       hello
       |EOF
       |""".stripMargin,
  )

  check(
    "keyword-syntax",
    "syntax",
    """|SYNTAX           syntax
       |EOF
       |""".stripMargin,
  )

  check(
    "string-double-quotes",
    "\"hello world\"",
    """|STRING           "hello world"
       |EOF
       |""".stripMargin,
  )

  check(
    "integer",
    "42",
    """|INTEGER          42
       |EOF
       |""".stripMargin,
  )

  check(
    "float",
    "3.14",
    """|FLOAT            3.14
       |EOF
       |""".stripMargin,
  )

  // Punctuation Tests
  check(
    "punctuation",
    "{}()[]<>;,=.",
    """|LEFT_BRACE       {
       |RIGHT_BRACE      }
       |LEFT_PAREN       (
       |RIGHT_PAREN      )
       |LEFT_BRACKET     [
       |RIGHT_BRACKET    ]
       |LEFT_ANGLE       <
       |RIGHT_ANGLE      >
       |SEMICOLON        ;
       |COMMA            ,
       |EQUALS           =
       |DOT              .
       |EOF
       |""".stripMargin,
  )

  // Comment Tests
  check(
    "single-line-comment",
    "// this is a comment",
    "EOF\n",
  )

  check(
    "multi-line-comment",
    "/* this is a \n multi-line comment */",
    "EOF\n",
  )

  // Edge Cases
  check(
    "unterminated-string",
    "\"hello",
    """|ILLEGAL          "hello
       |EOF
       |""".stripMargin,
  )

  check(
    "booleans",
    "true false",
    """|TRUE             true
       |FALSE            false
       |EOF
       |""".stripMargin,
  )

  check(
    "dotted-package",
    "com.example.test",
    """|IDENTIFIER       com
       |DOT              .
       |IDENTIFIER       example
       |DOT              .
       |IDENTIFIER       test
       |EOF
       |""".stripMargin,
  )

  // Whitespace Handling
  check(
    "whitespace",
    " \t\n\r syntax \t\n\r ",
    """|SYNTAX           syntax
       |EOF
       |""".stripMargin,
  )

  // Minimal Proto Constructs
  check(
    "minimal-message",
    "message User {}",
    """|MESSAGE          message
       |IDENTIFIER       User
       |LEFT_BRACE       {
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "minimal-field",
    "string name = 1;",
    """|STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |EOF
       |""".stripMargin,
  )

  check(
    "enumsAndOptions",
    """
    message SearchResponse {
      enum Corpus {
        UNIVERSAL = 0;
        WEB = 1;
        IMAGES = 2;
      }
      Corpus corpus = 1 [default = UNIVERSAL];
      
      message Result {
        string url = 1;
      }
    }
  """,
    """|MESSAGE          message
       |IDENTIFIER       SearchResponse
       |LEFT_BRACE       {
       |ENUM             enum
       |IDENTIFIER       Corpus
       |LEFT_BRACE       {
       |IDENTIFIER       UNIVERSAL
       |EQUALS           =
       |INTEGER          0
       |SEMICOLON        ;
       |IDENTIFIER       WEB
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |IDENTIFIER       IMAGES
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |IDENTIFIER       Corpus
       |IDENTIFIER       corpus
       |EQUALS           =
       |INTEGER          1
       |LEFT_BRACKET     [
       |IDENTIFIER       default
       |EQUALS           =
       |IDENTIFIER       UNIVERSAL
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |MESSAGE          message
       |IDENTIFIER       Result
       |LEFT_BRACE       {
       |STRING           string
       |IDENTIFIER       url
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "serviceWithRPC",
    """
     service UserService {
       rpc CreateUser(CreateUserRequest) returns (User) {}
       rpc GetUser(GetUserRequest) returns (User);
       rpc ListUsers(stream ListUsersRequest) returns (stream User) {}
     }
   """,
    """|SERVICE          service
       |IDENTIFIER       UserService
       |LEFT_BRACE       {
       |RPC              rpc
       |IDENTIFIER       CreateUser
       |LEFT_PAREN       (
       |IDENTIFIER       CreateUserRequest
       |RIGHT_PAREN      )
       |RETURNS          returns
       |LEFT_PAREN       (
       |IDENTIFIER       User
       |RIGHT_PAREN      )
       |LEFT_BRACE       {
       |RIGHT_BRACE      }
       |RPC              rpc
       |IDENTIFIER       GetUser
       |LEFT_PAREN       (
       |IDENTIFIER       GetUserRequest
       |RIGHT_PAREN      )
       |RETURNS          returns
       |LEFT_PAREN       (
       |IDENTIFIER       User
       |RIGHT_PAREN      )
       |SEMICOLON        ;
       |RPC              rpc
       |IDENTIFIER       ListUsers
       |LEFT_PAREN       (
       |STREAM           stream
       |IDENTIFIER       ListUsersRequest
       |RIGHT_PAREN      )
       |RETURNS          returns
       |LEFT_PAREN       (
       |STREAM           stream
       |IDENTIFIER       User
       |RIGHT_PAREN      )
       |LEFT_BRACE       {
       |RIGHT_BRACE      }
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "importsAndSyntax",
    """
     syntax = "proto3";
     import "google/protobuf/timestamp.proto";
     import public "other.proto";
     
     package com.example.api;
   """,
    """|SYNTAX           syntax
       |EQUALS           =
       |STRING           "proto3"
       |SEMICOLON        ;
       |IMPORT           import
       |STRING           "google/protobuf/timestamp.proto"
       |SEMICOLON        ;
       |IMPORT           import
       |IDENTIFIER       public
       |STRING           "other.proto"
       |SEMICOLON        ;
       |PACKAGE          package
       |IDENTIFIER       com
       |DOT              .
       |IDENTIFIER       example
       |DOT              .
       |IDENTIFIER       api
       |SEMICOLON        ;
       |EOF
       |""".stripMargin,
  )

  check(
    "repeatedAndMapFields",
    """
     message Profile {
       repeated string tags = 1;
       map<string, int32> scores = 2;
       repeated map<string, User> user_groups = 3;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       Profile
       |LEFT_BRACE       {
       |REPEATED         repeated
       |STRING           string
       |IDENTIFIER       tags
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |MAP              map
       |LEFT_ANGLE       <
       |STRING           string
       |COMMA            ,
       |INT32            int32
       |RIGHT_ANGLE      >
       |IDENTIFIER       scores
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |REPEATED         repeated
       |MAP              map
       |LEFT_ANGLE       <
       |STRING           string
       |COMMA            ,
       |IDENTIFIER       User
       |RIGHT_ANGLE      >
       |IDENTIFIER       user_groups
       |EQUALS           =
       |INTEGER          3
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "oneofConstruct",
    """
     message Payment {
       oneof payment_method {
         CreditCard credit_card = 1;
         BankTransfer bank_transfer = 2;
         string cash_amount = 3;
       }
       optional string description = 4;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       Payment
       |LEFT_BRACE       {
       |ONEOF            oneof
       |IDENTIFIER       payment_method
       |LEFT_BRACE       {
       |IDENTIFIER       CreditCard
       |IDENTIFIER       credit_card
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |IDENTIFIER       BankTransfer
       |IDENTIFIER       bank_transfer
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |STRING           string
       |IDENTIFIER       cash_amount
       |EQUALS           =
       |INTEGER          3
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       description
       |EQUALS           =
       |INTEGER          4
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "complexFieldOptions",
    """
     message ComplexMessage {
       string name = 1 [(validate.rules).string.min_len = 5, deprecated = true];
       int32 value = 2 [json_name = "customValue"];
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       ComplexMessage
       |LEFT_BRACE       {
       |STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |LEFT_BRACKET     [
       |LEFT_PAREN       (
       |IDENTIFIER       validate
       |DOT              .
       |IDENTIFIER       rules
       |RIGHT_PAREN      )
       |DOT              .
       |STRING           string
       |DOT              .
       |IDENTIFIER       min_len
       |EQUALS           =
       |INTEGER          5
       |COMMA            ,
       |IDENTIFIER       deprecated
       |EQUALS           =
       |TRUE             true
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |INT32            int32
       |IDENTIFIER       value
       |EQUALS           =
       |INTEGER          2
       |LEFT_BRACKET     [
       |IDENTIFIER       json_name
       |EQUALS           =
       |STRING           "customValue"
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "deeplyNestedStructures",
    """
     message Organization {
       message Department {
         message Team {
           enum Role { LEAD = 0; MEMBER = 1; }
           message Person {
             string name = 1;
             Role role = 2;
           }
           repeated Person members = 1;
         }
         repeated Team teams = 1;
       }
       repeated Department departments = 1;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       Organization
       |LEFT_BRACE       {
       |MESSAGE          message
       |IDENTIFIER       Department
       |LEFT_BRACE       {
       |MESSAGE          message
       |IDENTIFIER       Team
       |LEFT_BRACE       {
       |ENUM             enum
       |IDENTIFIER       Role
       |LEFT_BRACE       {
       |IDENTIFIER       LEAD
       |EQUALS           =
       |INTEGER          0
       |SEMICOLON        ;
       |IDENTIFIER       MEMBER
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |MESSAGE          message
       |IDENTIFIER       Person
       |LEFT_BRACE       {
       |STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |IDENTIFIER       Role
       |IDENTIFIER       role
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |REPEATED         repeated
       |IDENTIFIER       Person
       |IDENTIFIER       members
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |REPEATED         repeated
       |IDENTIFIER       Team
       |IDENTIFIER       teams
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |REPEATED         repeated
       |IDENTIFIER       Department
       |IDENTIFIER       departments
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "builtInTypes",
    """
     message AllTypes {
       double d = 1;
       float f = 2;
       int32 i32 = 3;
       int64 i64 = 4;
       uint32 ui32 = 5;
       uint64 ui64 = 6;
       sint32 si32 = 7;
       sint64 si64 = 8;
       fixed32 fx32 = 9;
       fixed64 fx64 = 10;
       sfixed32 sfx32 = 11;
       sfixed64 sfx64 = 12;
       bool b = 13;
       string s = 14;
       bytes by = 15;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       AllTypes
       |LEFT_BRACE       {
       |DOUBLE           double
       |IDENTIFIER       d
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |FLOAT            float
       |IDENTIFIER       f
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |INT32            int32
       |IDENTIFIER       i32
       |EQUALS           =
       |INTEGER          3
       |SEMICOLON        ;
       |INT64            int64
       |IDENTIFIER       i64
       |EQUALS           =
       |INTEGER          4
       |SEMICOLON        ;
       |UINT32           uint32
       |IDENTIFIER       ui32
       |EQUALS           =
       |INTEGER          5
       |SEMICOLON        ;
       |UINT64           uint64
       |IDENTIFIER       ui64
       |EQUALS           =
       |INTEGER          6
       |SEMICOLON        ;
       |SINT32           sint32
       |IDENTIFIER       si32
       |EQUALS           =
       |INTEGER          7
       |SEMICOLON        ;
       |SINT64           sint64
       |IDENTIFIER       si64
       |EQUALS           =
       |INTEGER          8
       |SEMICOLON        ;
       |FIXED32          fixed32
       |IDENTIFIER       fx32
       |EQUALS           =
       |INTEGER          9
       |SEMICOLON        ;
       |FIXED64          fixed64
       |IDENTIFIER       fx64
       |EQUALS           =
       |INTEGER          10
       |SEMICOLON        ;
       |SFIXED32         sfixed32
       |IDENTIFIER       sfx32
       |EQUALS           =
       |INTEGER          11
       |SEMICOLON        ;
       |SFIXED64         sfixed64
       |IDENTIFIER       sfx64
       |EQUALS           =
       |INTEGER          12
       |SEMICOLON        ;
       |BOOL             bool
       |IDENTIFIER       b
       |EQUALS           =
       |INTEGER          13
       |SEMICOLON        ;
       |STRING           string
       |IDENTIFIER       s
       |EQUALS           =
       |INTEGER          14
       |SEMICOLON        ;
       |BYTES            bytes
       |IDENTIFIER       by
       |EQUALS           =
       |INTEGER          15
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  // Proto2-specific tests
  check(
    "proto2-required-fields",
    """
     message User {
       required string name = 1;
       required int32 id = 2;
       optional string email = 3;
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       User
       |LEFT_BRACE       {
       |REQUIRED         required
       |STRING           string
       |IDENTIFIER       name
       |EQUALS           =
       |INTEGER          1
       |SEMICOLON        ;
       |REQUIRED         required
       |INT32            int32
       |IDENTIFIER       id
       |EQUALS           =
       |INTEGER          2
       |SEMICOLON        ;
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       email
       |EQUALS           =
       |INTEGER          3
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

  check(
    "proto2-default-values",
    """
     message Config {
       optional int32 timeout = 1 [default = 30];
       optional string mode = 2 [default = "production"];
       optional bool enabled = 3 [default = true];
     }
   """,
    """|MESSAGE          message
       |IDENTIFIER       Config
       |LEFT_BRACE       {
       |OPTIONAL         optional
       |INT32            int32
       |IDENTIFIER       timeout
       |EQUALS           =
       |INTEGER          1
       |LEFT_BRACKET     [
       |DEFAULT          default
       |EQUALS           =
       |INTEGER          30
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |OPTIONAL         optional
       |STRING           string
       |IDENTIFIER       mode
       |EQUALS           =
       |INTEGER          2
       |LEFT_BRACKET     [
       |DEFAULT          default
       |EQUALS           =
       |STRING           "production"
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |OPTIONAL         optional
       |BOOL             bool
       |IDENTIFIER       enabled
       |EQUALS           =
       |INTEGER          3
       |LEFT_BRACKET     [
       |DEFAULT          default
       |EQUALS           =
       |TRUE             true
       |RIGHT_BRACKET    ]
       |SEMICOLON        ;
       |RIGHT_BRACE      }
       |EOF
       |""".stripMargin,
  )

}
