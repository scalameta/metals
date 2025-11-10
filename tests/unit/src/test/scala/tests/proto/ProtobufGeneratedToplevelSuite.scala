package tests.proto

import scala.meta.inputs.Input
import scala.meta.internal.mtags.proto.ProtobufToplevelMtags

class ProtobufGeneratedToplevelSuite extends BaseProtobufToplevelSuite {
  override def createMtagsIndexer(
      input: Input.VirtualFile
  ): ProtobufToplevelMtags = {
    new ProtobufToplevelMtags(input, includeGeneratedSymbols = true)
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
       |com/example/User#zip_code().
       |com/example/User#zipCode().
       |com/example/User#getZipCode().
       |com/example/User#uncompressed_bytes().
       |com/example/User#uncompressedBytes().
       |com/example/User#getUncompressedBytes().
       |com/example/User#house_data.
       |com/example/User#HouseDataCase#
       |com/example/User#house_data.duplex().
       |com/example/User#house_data.DUPLEX#
       |com/example/User#house_data.full_house().
       |com/example/User#house_data.FULL_HOUSE#
       |com/example/User#House#
       |com/example/User#House#rooms().
       |com/example/User#House#getRooms().
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
       |""".stripMargin,
  )

}
