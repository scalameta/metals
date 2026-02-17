package tests.proto

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsIndexer
import scala.meta.internal.mtags.proto.ProtoMtagsV1
import scala.meta.internal.mtags.proto.ProtoMtagsV2

/**
 * Tests V1 indexer with includeGeneratedSymbols=true.
 * This generates camelCase variations of proto field names (for ScalaPB).
 */
class ProtobufGeneratedToplevelSuite extends BaseProtobufToplevelSuite {
  override def createMtagsIndexer(
      input: Input.VirtualFile
  ): MtagsIndexer = {
    new ProtoMtagsV1(input, includeGeneratedSymbols = true)
  }

  check(
    "basic",
    """
    syntax = "proto3"; // Define syntax version
    package com.example;
    message User {
      string name = 1;
      int32 zip_code = 2;
      int64 uncompressed_bytes = 3;
      oneof house_data {
        bool duplex = 3;
        House full_house = 4;
      }
      message House {
        int32 rooms = 1;
      }
    }
    """,
    """|com/
       |com/example/
       |com/example/User#
       |com/example/User#name().
       |com/example/User#getName().
       |com/example/User#withName().
       |com/example/User#zip_code().
       |com/example/User#zipCode().
       |com/example/User#getZipCode().
       |com/example/User#withZipCode().
       |com/example/User#uncompressed_bytes().
       |com/example/User#uncompressedBytes().
       |com/example/User#getUncompressedBytes().
       |com/example/User#withUncompressedBytes().
       |com/example/User#house_data.
       |com/example/User#HouseDataCase#
       |com/example/User#house_data.duplex().
       |com/example/User#house_data.DUPLEX#
       |com/example/User#house_data.full_house().
       |com/example/User#house_data.FULL_HOUSE#
       |com/example/User#House#
       |com/example/User#House#rooms().
       |com/example/User#House#getRooms().
       |com/example/User#House#withRooms().
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
       |Payment#PaymentMethodCase#
       |Payment#payment_method.credit_card().
       |Payment#payment_method.CREDIT_CARD#
       |Payment#payment_method.bank_transfer().
       |Payment#payment_method.BANK_TRANSFER#
       |Payment#payment_method.cash_amount().
       |Payment#payment_method.CASH_AMOUNT#
       |Payment#description().
       |Payment#getDescription().
       |Payment#withDescription().
       |""".stripMargin,
  )

}

/**
 * Tests V2 indexer with generated symbols.
 * V2 uses Language tags to distinguish Java vs Scala symbols.
 */
class ProtobufV2GeneratedToplevelSuite extends BaseProtobufToplevelSuite {
  override def createMtagsIndexer(
      input: Input.VirtualFile
  ): MtagsIndexer = {
    new ProtoMtagsV2(
      input,
      includeMembers = true,
      includeGeneratedSymbols = true,
    )
  }

  check(
    "v2-basic",
    """
    syntax = "proto3";
    package com.example;
    
    message User {
      string name = 1;
      int32 zip_code = 2;
    }
    """,
    // V2 generates: proto field, camelCase (Scala), withX (Scala), getX/hasX/getXBytes (Java)
    """|com/
       |com/example/
       |com/example/User#
       |com/example/User#name().
       |com/example/User#withName().
       |com/example/User#getName().
       |com/example/User#hasName().
       |com/example/User#getNameBytes().
       |com/example/User#zip_code().
       |com/example/User#zipCode().
       |com/example/User#withZipCode().
       |com/example/User#getZipCode().
       |com/example/User#hasZipCode().
       |com/example/User#getZipCodeBytes().
       |""".stripMargin,
  )
}
