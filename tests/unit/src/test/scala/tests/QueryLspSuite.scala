package tests

import scala.concurrent.Future
import scala.meta.internal.metals.{MetalsEnrichments, BuildInfo => V}
import scala.meta.internal.query._
import com.google.gson.JsonElement
import java.util.concurrent.CompletableFuture
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonArray
import scala.jdk.CollectionConverters._
import scala.meta.internal.metals.GlobSearchResponse
import scala.concurrent.Await
import scala.concurrent.duration._

class QueryLspSuite extends BaseLspSuite("query") {

  test("findPackage") {
    val fut = for {
      _ <- initialize(
        """
          |/metals.json
          |{"a":
          |  { 
          |    "scalaVersion": "3.3.5",
          |    "libraryDependencies": [
          |       "org.virtuslab::besom-core:0.3.2",
          |       "org.virtuslab::besom-aws:6.31.0-core.0.3"
          |    ]
          |  } 
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      locations = server.server.queryEngine.findPackage("docdb").mkString("\n")
      _ = {
        pprint.log(s"query: findPackage(\"docdb\"), result:")
        pprint.log(locations)
        pprint.log("============")
      }
      expected = """|besom/api/aws/docdb/inputs/
                    |besom/api/aws/docdb/
                    |besom/api/aws/docdb/outputs/""".stripMargin
      _ = assertEquals(
        locations,
        expected,
      )
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

  test("glob search all types - workspace") {
    val fut = for {
      _ <- initialize(
        s"""
           |/src/main/scala/com/test/TestClass.scala
           |package com.test
           |
           |class TestClass {
           |  def testMethod(param: String): String = param
           |  val testValue: Int = 42
           |}
           |
           |object TestObject {
           |  def apply(): TestClass = new TestClass()
           |  def testFunction(x: Int): Int = x * 2
           |}
           |
           |trait TestTrait {
           |  def abstractMethod(x: Double): Double
           |}
           |
           |/src/main/scala/com/test/matching/MatchingUtil.scala
           |package com.test.matching
           |
           |object MatchingUtil {
           |  def matchPattern(input: String, pattern: String): Boolean = {
           |    input.matches(pattern)
           |  }
           |}
           |
           |/src/main/scala/com/test/NonMatching.scala
           |package com.test
           |
           |// This class should NOT match "test" queries but should match "matching" queries
           |class NonMatchingClass {
           |  def someMethod(): Unit = ()
           |  val someValue: Int = 100
           |}
           |
           |// This object should NOT match "test" queries or "matching" queries
           |object HelperObject {
           |  def helperFunction(): Unit = ()
           |}
           |
           |/src/main/scala/com/other/OtherPackage.scala
           |package com.other
           |
           |// This should NOT match "matching" queries or "test" queries
           |object OtherUtil {
           |  def otherPattern(): Boolean = true
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("src/main/scala/com/test/TestClass.scala")
      _ <- server.didOpen("src/main/scala/com/test/matching/MatchingUtil.scala")
      _ <- server.didOpen("src/main/scala/com/test/NonMatching.scala")
      _ <- server.didOpen("src/main/scala/com/other/OtherPackage.scala")
      _ = assertNoDiagnostics()

      // Test searching for "test" - should find packages, classes, objects, traits
      _ = {
        val testResults =
          server.server.queryEngine.globSearch("test", Set.empty)
        // should find:
        // packages: com.test
        // classes: com.test.TestClass,
        // objects: com.test.TestObject
        // traits: com.test.TestTrait
        // methods: com.test.TestClass.testMethod
        // functions: com.test.TestObject.testFunction

        pprint.log(s"query: globSearch(\"test\", Set.empty), result:")
        pprint.log(testResults)
        pprint.log("============")

        // Positive assertions
        assert(
          testResults.exists(r =>
            r.name == "com.test" && r.symbolType == SymbolType.Package
          )
        )
        assert(
          testResults.exists(r =>
            r.name == "TestClass" && r.symbolType == SymbolType.Class
          )
        )
        assert(
          testResults.exists(r =>
            r.name == "TestObject" && r.symbolType == SymbolType.Object
          )
        )
        assert(
          testResults.exists(r =>
            r.name == "TestTrait" && r.symbolType == SymbolType.Trait
          )
        )

        // Negative assertions - these should NOT match
        assert(
          !testResults.exists(r => r.name == "NonMatchingClass")
        )
        assert(
          !testResults.exists(r => r.name == "HelperObject")
        )
        assert(
          !testResults.exists(r => r.name == "someMethod")
        )
        assert(
          !testResults.exists(r => r.name == "helperFunction")
        )
      }

      // Test searching for "matching" - should find package and object
      _ = {
        val matchingResults = server.server.queryEngine
          .globSearch("matching", Set.empty, enableDebug = true)
        // should find:
        // package: com.test.matching
        // class: NonMatchingClass (contains "matching" in name)
        // object: MatchingUtil

        pprint.log(s"query: globSearch(\"matching\", Set.empty), result:")
        pprint.log(matchingResults)
        pprint.log("============")

        // Positive assertions
        assert(
          matchingResults.exists(r =>
            r.name == "matching" && r.symbolType == SymbolType.Package
          )
        )
        assert(
          matchingResults.exists(r =>
            r.name == "MatchingUtil" && r.symbolType == SymbolType.Object
          )
        )

        // Negative assertions - these should NOT match
        assert(
          !matchingResults.exists(r => r.name == "OtherUtil")
        )
        assert(
          !matchingResults.exists(r => r.name == "otherPattern")
        )
        assert(
          !matchingResults.exists(r => r.path.contains("com.other"))
        )
      }

      // Test searching for "test" with class filter
      _ = {
        val classResults = server.server.queryEngine
          .globSearch(
            "test",
            Set(SymbolType.Class),
          )
        // should find:
        // classes: com.test.TestClass,

        pprint.log(
          s"query: globSearch(\"test\", Set(SymbolType.Class)), result:"
        )
        pprint.log(classResults)
        pprint.log("============")
        assert(classResults.forall(r => r.symbolType == SymbolType.Class))
        assert(classResults.exists(r => r.name == "TestClass"))
        assert(!classResults.exists(r => r.name == "TestObject"))
      }

      // Test searching for methods
      _ = {
        val methodResults = server.server.queryEngine
          .globSearch(
            "method",
            Set(SymbolType.Method, SymbolType.Function),
          )
        // should find:
        // methods: com.test.TestClass.testMethod, com.test.TestTrait.abstractMethod

        pprint.log(
          s"query: globSearch(\"method\", Set(SymbolType.Method, SymbolType.Function)), result:"
        )
        pprint.log(methodResults)
        pprint.log("============")
        assert(
          methodResults.forall(r =>
            r.symbolType == SymbolType.Function || r.symbolType == SymbolType.Method
          )
        )
        assert(methodResults.exists(r => r.name == "testMethod"))
        assert(methodResults.exists(r => r.name == "abstractMethod"))
      }
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

  test("glob search case insensitive - workspace") {
    val fut = for {
      _ <- initialize(
        s"""
           |/src/main/scala/com/test/CaseSensitivity.scala
           |package com.test
           |
           |class CamelCaseClass {
           |  def camelCaseMethod(): Unit = ()
           |}
           |
           |object UPPERCASE_OBJECT {
           |  def UPPERCASE_METHOD = 100
           |}
           |
           |object lowercase_object {
           |  def lowercase_method(): Unit = ()
           |}
           |
           |// Adversary samples that should NOT match specific queries
           |class ComelPrefix { // typo
           |  // Shouldn't match any query
           |}
           |
           |object SUPERCASE {
           |  // Shouldn't match any query
           |}
           |
           |class FuNnyCaSe {
           |  // Shouldn't match any query
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("src/main/scala/com/test/CaseSensitivity.scala")
      _ = assertNoDiagnostics()

      // Case insensitive search for "camel"
      _ = {
        val camelResults = server.server.queryEngine
          .globSearch("camel", Set.empty)
        // should find:
        // class: CamelCaseClass
        // method: camelCaseMethod

        pprint.log(s"query: globSearch(\"camel\", Set.empty), result:")
        pprint.log(camelResults)
        pprint.log("============")

        // Positive assertions
        assert(camelResults.exists(r => r.name == "CamelCaseClass"))
        assert(camelResults.exists(r => r.name == "camelCaseMethod"))

        // Negative assertions
        assert(!camelResults.exists(r => r.name == "FuNnyCaSe"))
        assert(!camelResults.exists(r => r.name == "ComelPrefix"))
        assert(!camelResults.exists(r => r.name == "SUPERCASE"))
      }

      // Case insensitive search for "UPPERCASE"
      _ = {
        val upperResults = server.server.queryEngine
          .globSearch("uppercase", Set.empty)
        // should find:
        // object: UPPERCASE_OBJECT
        // method: UPPERCASE_METHOD

        pprint.log(s"query: globSearch(\"uppercase\", Set.empty), result:")
        pprint.log(upperResults)
        pprint.log("============")

        // Positive assertions
        assert(upperResults.exists(r => r.name == "UPPERCASE_OBJECT"))
        assert(upperResults.exists(r => r.name == "UPPERCASE_METHOD"))

        // Negative assertions
        assert(!upperResults.exists(r => r.name == "SUPERCASE"))
        assert(!upperResults.exists(r => r.name == "FuNnyCaSe"))
        assert(!upperResults.exists(r => r.name == "ComelPrefix"))
      }

      // Case insensitive search for "lowercase"
      _ = {
        val lowerResults = server.server.queryEngine
          .globSearch("lowercase", Set.empty)
        // should find:
        // object: lowercase_object
        // method: lowercase_method

        pprint.log(s"query: globSearch(\"lowercase\", Set.empty), result:")
        pprint.log(lowerResults)
        pprint.log("============")

        // Positive assertions
        assert(lowerResults.exists(r => r.name == "lowercase_object"))
        assert(lowerResults.exists(r => r.name == "lowercase_method"))

        // Negative assertions
        assert(!lowerResults.exists(r => r.name == "FuNnyCaSe"))
        assert(!lowerResults.exists(r => r.name == "ComelPrefix"))
        assert(!lowerResults.exists(r => r.name == "SUPERCASE"))
      }
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

  test("glob search packages - workspace") {
    val fut = for {
      _ <- initialize(
        s"""
           |/src/main/scala/com/test/nested/package1/Class1.scala
           |package com.test.nested.package1
           |
           |class Class1
           |
           |/src/main/scala/com/test/nested/package2/Class2.scala
           |package com.test.nested.package2
           |
           |class Class2
           |
           |/src/main/scala/com/example/ExampleClass.scala
           |package com.example
           |
           |class ExampleClass
           |
           |/src/main/scala/com/test/pkgtools/NotAPackage.scala
           |package com.test.pkgtools
           |
           |// Should NOT match "package" query
           |class NotAPackage
           |
           |/src/main/scala/org/test/DistantTest.scala
           |package org.test
           |
           |// Should NOT match "com.test" query
           |class DistantTest
           |
           |/src/main/scala/com/test/elements/NotNested.scala
           |package com.test.elements
           |
           |// Should NOT match "nested" query
           |class NotNested
           |""".stripMargin
      )
      _ <- server.didOpen(
        "src/main/scala/com/test/nested/package1/Class1.scala"
      )
      _ <- server.didOpen(
        "src/main/scala/com/test/nested/package2/Class2.scala"
      )
      _ <- server.didOpen("src/main/scala/com/example/ExampleClass.scala")
      _ <- server.didOpen("src/main/scala/com/test/pkgtools/NotAPackage.scala")
      _ <- server.didOpen("src/main/scala/org/test/DistantTest.scala")
      _ <- server.didOpen("src/main/scala/com/test/elements/NotNested.scala")
      _ = assertNoDiagnostics()

      // Search for all packages
      _ = {
        val packageResults = server.server.queryEngine
          .globSearch(
            "package",
            Set(SymbolType.Package),
          )
        // should find:
        // packages: com.test.nested.package1, com.test.nested.package2

        pprint.log(
          s"query: globSearch(\"package\", Set(SymbolType.Package)), result:"
        )
        pprint.log(packageResults)
        pprint.log("============")

        // Positive assertions
        assert(packageResults.forall(r => r.symbolType == SymbolType.Package))
        assert(packageResults.exists(r => r.name == "package1"))
        assert(packageResults.exists(r => r.name == "package2"))

        // Negative assertions
        assert(!packageResults.exists(r => r.name == "NotAPackage"))
        assert(!packageResults.exists(r => r.name == "pkgtools"))
      }

      // Search for test packages
      _ = {
        val comTestResults = server.server.queryEngine
          .globSearch(
            "test",
            Set(SymbolType.Package),
          )
        // should find:
        // packages: com.test, org.test

        pprint.log(
          s"query: globSearch(\"test\", Set(SymbolType.Package)), result:"
        )
        pprint.log(comTestResults)
        pprint.log("============")

        // Positive assertions
        assert(comTestResults.exists(r => r.path.contains("com.test")))
        assert(comTestResults.exists(r => r.path.contains("org.test")))

        // Negative assertions
        assert(!comTestResults.exists(r => r.path.contains("com.example")))
        assert(!comTestResults.exists(r => r.name == "DistantTest"))
      }

      // Search for nested packages
      _ = {
        val nestedResults = server.server.queryEngine
          .globSearch(
            "nested",
            Set(SymbolType.Package),
          )
        // should find:
        // packages: com.test.nested

        pprint.log(
          s"query: globSearch(\"nested\", Set(SymbolType.Package)), result:"
        )
        pprint.log(nestedResults)
        pprint.log("============")

        // Positive assertions
        assert(nestedResults.exists(r => r.name == "nested"))
        assert(nestedResults.exists(r => r.path.contains("com.test.nested")))

        // Negative assertions
        assert(!nestedResults.exists(r => r.name == "NotNested"))
        assert(!nestedResults.exists(r => r.path.contains("elements")))
      }
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

}
