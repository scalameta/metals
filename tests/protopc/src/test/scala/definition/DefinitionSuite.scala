package definition

import tests.pc.BaseProtoDefinitionSuite

class DefinitionSuite extends BaseProtoDefinitionSuite {

  checkSymbol(
    "message-definition-symbol",
    """
      |syntax = "proto3";
      |message Per@@son {
      |  string name = 1;
      |}
      |""".stripMargin,
    "Person#",
  )

  checkSymbol(
    "field-definition-symbol",
    """
      |syntax = "proto3";
      |message Person {
      |  string na@@me = 1;
      |}
      |""".stripMargin,
    "name.",
  )

  checkSymbol(
    "enum-definition-symbol",
    """
      |syntax = "proto3";
      |enum Sta@@tus {
      |  UNKNOWN = 0;
      |}
      |""".stripMargin,
    "Status#",
  )

  checkSymbol(
    "enum-value-definition-symbol",
    """
      |syntax = "proto3";
      |enum Status {
      |  UNK@@NOWN = 0;
      |}
      |""".stripMargin,
    "UNKNOWN.",
  )

  checkSymbol(
    "type-reference-definition",
    """
      |syntax = "proto3";
      |message Address {}
      |message Person {
      |  Add@@ress home = 1;
      |}
      |""".stripMargin,
    "Address#",
  )

  // Test cross-file type reference
  checkFiles(
    "cross-file-type-reference",
    Map(
      "common.proto" ->
        """syntax = "proto3";
          |message SharedMessage {
          |  string value = 1;
          |}
          |""".stripMargin,
      "user.proto" ->
        """syntax = "proto3";
          |import "common.proto";
          |message User {
          |  Shared@@Message data = 1;
          |}
          |""".stripMargin,
    ),
    "user.proto",
    "SharedMessage#",
  )

  // Test clicking on fully-qualified type reference
  checkFiles(
    "fully-qualified-type-reference",
    Map(
      "types.proto" ->
        """syntax = "proto3";
          |package my.pkg;
          |message Range {
          |  int32 start = 1;
          |}
          |""".stripMargin,
      "usage.proto" ->
        """syntax = "proto3";
          |import "types.proto";
          |message MyMessage {
          |  my.pkg.Ra@@nge location = 1;
          |}
          |""".stripMargin,
    ),
    "usage.proto",
    "my/pkg/Range#",
  )

  // Test goto-definition for oneof field type reference
  checkSymbol(
    "oneof-field-type-reference",
    """
      |syntax = "proto3";
      |message ClassSignature {
      |  string name = 1;
      |}
      |message Signature {
      |  oneof sealed_value {
      |    Class@@Signature class_signature = 1;
      |  }
      |}
      |""".stripMargin,
    "ClassSignature#",
  )

  // Test goto-definition for the oneof field name itself
  checkSymbol(
    "oneof-field-name-definition",
    """
      |syntax = "proto3";
      |message ClassSignature {
      |  string name = 1;
      |}
      |message Signature {
      |  oneof sealed_value {
      |    ClassSignature class_sig@@nature = 1;
      |  }
      |}
      |""".stripMargin,
    "class_signature.",
  )

  // Test goto-definition for the oneof group name itself
  checkSymbol(
    "oneof-group-name-definition",
    """
      |syntax = "proto3";
      |message Signature {
      |  oneof sealed@@_value {
      |    string name = 1;
      |  }
      |}
      |""".stripMargin,
    "sealed_value.",
  )
}
