package tests.proto

import scala.meta.inputs.Input
import scala.meta.internal.mtags.proto.ProtobufToplevelMtags

import munit.TestOptions

abstract class BaseProtobufToplevelSuite extends munit.FunSuite {
  def createMtags(input: Input.VirtualFile): ProtobufToplevelMtags = {
    new ProtobufToplevelMtags(input)
  }
  def check(
      name: TestOptions,
      protoFileContent: String,
      expectedSymbols: String,
  ): Unit =
    test(name) {
      val input = Input.VirtualFile("example.proto", protoFileContent)
      val mtags = createMtags(input)
      val doc = mtags.index()
      assertNoDiff(
        doc.occurrences
          .map(o => o.symbol)
          .mkString("\n"),
        expectedSymbols,
      )
    }
}
class ProtobufToplevelSuite extends BaseProtobufToplevelSuite {

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
    """|com/
       |com/example/
       |com/example/UserService#
       |com/example/UserService#GetUser().
       |com/example/User#
       |com/example/User#name().
       |com/example/User#id().
       |com/example/User#account_balance().
       |""".stripMargin,
  )

  // Core Token Types (Individual Focus) - these generate no symbols
  check(
    "empty",
    "",
    "",
  )

  check(
    "identifier",
    "hello",
    "",
  )

  check(
    "keyword-syntax",
    "syntax",
    "",
  )

  check(
    "string-double-quotes",
    "\"hello world\"",
    "",
  )

  check(
    "integer",
    "42",
    "",
  )

  check(
    "float",
    "3.14",
    "",
  )

  // Punctuation Tests - no symbols generated
  check(
    "punctuation",
    "{}()[]<>;,=.",
    "",
  )

  // Comment Tests - no symbols generated
  check(
    "single-line-comment",
    "// this is a comment",
    "",
  )

  check(
    "multi-line-comment",
    "/* this is a \n multi-line comment */",
    "",
  )

  // Edge Cases - no symbols generated
  check(
    "unterminated-string",
    "\"hello",
    "",
  )

  check(
    "booleans",
    "true false",
    "",
  )

  check(
    "dotted-package",
    "com.example.test",
    "",
  )

  // Whitespace Handling - no symbols generated
  check(
    "whitespace",
    " \t\n\r syntax \t\n\r ",
    "",
  )

  // Minimal Proto Constructs
  check(
    "minimal-message",
    "message User {}",
    """|User#
       |""".stripMargin,
  )

  check(
    "minimal-field",
    "string name = 1;",
    "", // No symbols - field must be inside a message/enum
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
    """|SearchResponse#
       |SearchResponse#Corpus#
       |SearchResponse#Corpus#UNIVERSAL().
       |SearchResponse#Corpus#WEB().
       |SearchResponse#Corpus#IMAGES().
       |SearchResponse#corpus().
       |SearchResponse#Result#
       |SearchResponse#Result#url().
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
    """|UserService#
       |UserService#CreateUser().
       |UserService#GetUser().
       |UserService#ListUsers().
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
    """|com/
       |com/example/
       |com/example/api/
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
    """|Profile#
       |Profile#tags().
       |Profile#scores().
       |Profile#user_groups().
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
    """|Payment#
       |Payment#payment_method.
       |Payment#payment_method.credit_card().
       |Payment#payment_method.bank_transfer().
       |Payment#payment_method.cash_amount().
       |Payment#description().
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
    """|ComplexMessage#
       |ComplexMessage#name().
       |ComplexMessage#value().
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
    """|Organization#
       |Organization#Department#
       |Organization#Department#Team#
       |Organization#Department#Team#Role#
       |Organization#Department#Team#Role#LEAD().
       |Organization#Department#Team#Role#MEMBER().
       |Organization#Department#Team#Person#
       |Organization#Department#Team#Person#name().
       |Organization#Department#Team#Person#role().
       |Organization#Department#Team#members().
       |Organization#Department#teams().
       |Organization#departments().
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
    """|AllTypes#
       |AllTypes#d().
       |AllTypes#f().
       |AllTypes#i32().
       |AllTypes#i64().
       |AllTypes#ui32().
       |AllTypes#ui64().
       |AllTypes#si32().
       |AllTypes#si64().
       |AllTypes#fx32().
       |AllTypes#fx64().
       |AllTypes#sfx32().
       |AllTypes#sfx64().
       |AllTypes#b().
       |AllTypes#s().
       |AllTypes#by().
       |""".stripMargin,
  )

}
