package tests

import scala.concurrent.Await
import scala.concurrent.duration._

import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.SymbolType
class QueryLspSuite extends BaseLspSuite("query") {

  // @kasiaMarek: missing:
  // - methods / values from dependencies (to think about)
  // - type aliases from dependencies ???
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
           |case class TestCaseClass()
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
      _ = assertNoDiagnostics()

      // Test searching for "test" - should find packages, classes, objects, traits
      _ = assertNoDiff(
        timed(
          server.server.queryEngine.globSearch("test", Set.empty)
        ).show,
        """|class com.test.TestCaseClass
           |class com.test.TestClass
           |class java.awt.dnd.SerializationTester
           |method com.test.TestClass.testMethod
           |method com.test.TestClass.testValue
           |method com.test.TestObject.testFunction
           |object com.test.TestObject
           |object scala.reflect.TypeTest
           |package com.test
           |trait com.test.TestTrait
           |trait scala.quoted.Quotes.reflectModule.TypedOrTestMethods
           |trait scala.quoted.Quotes.reflectModule.TypedOrTestModule
           |trait scala.reflect.TypeTest
           |""".stripMargin,
        "query: globSearch(\"test\", Set.empty)",
      )

      // Test searching for "matching" - should find package and object
      _ = assertNoDiff(
        server.server.queryEngine.globSearch("matching", Set.empty).show,
        """|class com.test.NonMatchingClass
           |object com.test.matching.MatchingUtil
           |object scala.quoted.runtime.QuoteMatching
           |package com.test.matching
           |trait scala.quoted.runtime.QuoteMatching
           |""".stripMargin,
        "query: globSearch(\"matching\", Set.empty)",
      )

      // Test searching for "test" with class filter
      _ = assertNoDiff(
        server.server.queryEngine
          .globSearch("test", Set(SymbolType.Class))
          .show,
        """|class com.test.TestClass
           |class java.awt.dnd.SerializationTester
           |package com.test
           |""".stripMargin,
        "query: globSearch(\"test\", Set(SymbolType.Class))",
      )

      // Test searching for methods
      _ = assertNoDiff(
        server.server.queryEngine
          .globSearch("method", Set(SymbolType.Method, SymbolType.Function))
          .show,
        """|method com.test.NonMatchingClass.someMethod
           |method com.test.TestClass.testMethod
           |method com.test.TestTrait.abstractMethod
           |""".stripMargin,
        "query: globSearch(\"test\", Set(SymbolType.Class))",
      )
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
      _ = assertNoDiff(
        server.server.queryEngine.globSearch("camel", Set.empty).show,
        """|class com.test.CamelCaseClass
           |method com.test.CamelCaseClass.camelCaseMethod
           |""".stripMargin,
      )

      // Case insensitive search for "UPPERCASE"
      _ = assertNoDiff(
        server.server.queryEngine.globSearch("uppercase", Set.empty).show,
        """|class javax.swing.text.MaskFormatter.UpperCaseCharacter
           |method com.test.UPPERCASE_OBJECT.UPPERCASE_METHOD
           |object com.test.UPPERCASE_OBJECT
           |""".stripMargin,
      )

      // Case insensitive search for "lowercase"
      _ = assertNoDiff(
        server.server.queryEngine.globSearch("lowercase", Set.empty).show,
        """|class javax.swing.text.MaskFormatter.LowerCaseCharacter
           |method com.test.lowercase_object.lowercase_method
           |object com.test.lowercase_object
           |""".stripMargin,
      )
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
      _ = assertNoDiagnostics()

      // Search for all packages
      _ = assertNoDiff(
        server.server.queryEngine
          .globSearch(
            "package",
            Set(SymbolType.Package),
          )
          .show,
        """|package com.test.nested.package1
           |package com.test.nested.package2
           |""".stripMargin,
      )

      // Search for test packages
      _ = assertNoDiff(
        server.server.queryEngine
          .globSearch(
            "test",
            Set(SymbolType.Package),
          )
          .show,
        """|package com.test
           |package org.test
           |""".stripMargin,
      )

      // Search for nested packages
      _ = assertNoDiff(
        server.server.queryEngine
          .globSearch(
            "nested",
            Set(SymbolType.Package),
          )
          .show,
        """|package com.test.nested
           |""".stripMargin,
      )
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

  test("inspect") {
    cleanWorkspace()
    val fut = for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/nested/package1/Class1.scala
           |package com.test.nested.package1
           |
           |class Class1(m: Int) {
           |  def add(x: Int, y: Int): Int = x + y
           |  def substract(x: Int, y: Int): Int = x - y
           |}
           |
           |object Class1 {
           |  def someFunction(x: Int): Int = x * 2
           |}
           |
           |/a/src/main/scala/com/test/nested/package2/Class2.scala
           |package com.test.nested.package2
           |
           |class Class2
           |
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/com/test/nested/package1/Class1.scala"
      )
      _ = assertNoDiagnostics()
      res <- server.server.queryEngine.inspect(
        "com.test.nested.package1.Class1"
      )

      _ = assertNoDiff(
        res.show,
        """|class com.test.nested.package1.Class1
           |	 - constructor Class1(m: Int)
           |	 - method add(x: Int, y: Int)Int
           |	 - method substract(x: Int, y: Int)Int
           |object com.test.nested.package1.Class1
           |	 - function someFunction(x: Int)Int
           |""".stripMargin,
      )
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

  test("docstrings") {
    cleanWorkspace()
    val fut = for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/nested/package1/Class1.scala
           |package com.test.nested.package1
           |
           |class Class1(m: Int) {
           |  /**
           |  * Adds two integers
           |  * @param x first argument
           |  * @param y second argument
           |  * @return sum of x and y
           |  */
           |  def add(x: Int, y: Int): Int = x + y
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/com/test/nested/package1/Class1.scala"
      )
      _ = assertNoDiagnostics()
      res <- server.server.queryEngine.getDocumentation(
        "com.test.nested.package1.Class1.add"
      )

      _ = assertNoDiff(
        res.map(_.show).getOrElse(""),
        """|Adds two integers
           | - y - second argument
           | - x - first argument
           |sum of x and y 
           |""".stripMargin,
      )
    } yield ()

    try Await.result(fut, 10.seconds)
    finally cancelServer()
  }

  def timed[T](f: => T): T = {
    val start = System.currentTimeMillis()
    val res = f
    val time = System.currentTimeMillis() - start
    scribe.info(s"Time taken: ${time}ms")
    res
  }
}
