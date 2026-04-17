package pc

import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser

import munit.FunSuite

/**
 * Test suite for proto parser edge cases that were causing indexing errors.
 * These tests verify that various proto syntax features are parsed without exceptions.
 */
class ParserEdgeCasesSuite extends FunSuite {

  def parseOk(name: String, code: String): Unit = {
    test(name) {
      val source = new SourceFile("test.proto", code)
      val result = Parser.parse(source)
      // Just verify we can parse without exception
      assert(result != null, s"Parser returned null for: $name")
    }
  }

  parseOk(
    "nested-extend-in-message",
    """|syntax = "proto2";
       |import "google/protobuf/descriptor.proto";
       |message Wrapper {
       |  extend google.protobuf.FieldOptions {
       |    optional string custom_option = 50001;
       |  }
       |}
       |""".stripMargin,
  )

  parseOk(
    "extend-with-repeated-field",
    """|syntax = "proto2";
       |import "google/protobuf/descriptor.proto";
       |message Tag {
       |  optional string name = 1;
       |}
       |message Wrapper {
       |  extend google.protobuf.FieldOptions {
       |    repeated Tag tags = 50003;
       |  }
       |}
       |""".stripMargin,
  )

  parseOk(
    "default-inf-values",
    """|syntax = "proto2";
       |message DefaultsTest {
       |  optional double d1 = 1 [default=inf];
       |  optional double d2 = 2 [default=-inf];
       |  optional double d3 = 3 [default=nan];
       |  optional float f1 = 4 [default=inf];
       |  optional float f2 = 5 [default=-inf];
       |  optional float f3 = 6 [default=nan];
       |}
       |""".stripMargin,
  )

  parseOk(
    "leading-dot-type-in-message",
    """|syntax = "proto3";
       |message Outer {
       |  .some.fully.qualified.Type field = 1;
       |}
       |""".stripMargin,
  )

  parseOk(
    "leading-dot-type-in-oneof",
    """|syntax = "proto3";
       |message Container {
       |  oneof choice {
       |    string simple = 1;
       |    .some.qualified.Type qualified = 2;
       |  }
       |}
       |""".stripMargin,
  )

  parseOk(
    "large-enum-values",
    """|syntax = "proto2";
       |enum TestEnum {
       |  UNSPECIFIED = 0;
       |  MAX_VALUE = 2147483647;
       |  MIN_VALUE = -2147483648;
       |}
       |""".stripMargin,
  )

  parseOk(
    "large-field-numbers",
    """|syntax = "proto2";
       |message LargeFields {
       |  optional string field1 = 536870911;
       |}
       |""".stripMargin,
  )

  parseOk(
    "message-with-extensions-range",
    """|syntax = "proto2";
       |message BaseMessage {
       |  extensions 1000 to 2000;
       |}
       |message Extension {
       |  extend BaseMessage {
       |    optional int32 opt_int = 1001;
       |    optional string opt_string = 1002;
       |  }
       |}
       |""".stripMargin,
  )

  parseOk(
    "group-declaration",
    """|syntax = "proto2";
       |message PropertyValue {
       |  optional int64 int64Value = 1;
       |  optional group PointValue = 5 {
       |    required double x = 6;
       |    required double y = 7;
       |  }
       |  optional group UserValue = 8 {
       |    required string email = 9;
       |    optional string nickname = 10;
       |  }
       |}
       |""".stripMargin,
  )

  parseOk(
    "string-concatenation-in-option",
    """|syntax = "proto3";
       |service Storage {
       |  option (google.api.oauth_scopes) =
       |      "https://www.googleapis.com/auth/cloud-platform,"
       |      "https://www.googleapis.com/auth/devstorage.full_control";
       |}
       |""".stripMargin,
  )

  parseOk(
    "string-concatenation-in-field-option",
    """|syntax = "proto3";
       |message Response {
       |  optional string logs = 1 [
       |    (openapi.field).example =
       |      "\"[hc4q8] [2023-01-10 19:12:58 +0000] [2] [INFO] Starting gunicorn\n\""
       |      "[hc4q8] [2023-01-10 19:12:58 +0000] [2] [INFO] Listening at: http://0.0.0.0:8080\n"
       |      "[hc4q8] [2023-01-10 19:12:58 +0000] [2] [INFO] Using worker: sync\n"
       |  ];
       |}
       |""".stripMargin,
  )
}
