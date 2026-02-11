package tests.mcp

import java.nio.file.Path

import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.SymbolType

import tests.BaseLspSuite

class McpQueryLspSuite extends BaseLspSuite("query") {

  // @kasiaMarek: missing:
  // - methods / values from dependencies (to think about)
  // - type aliases from dependencies
  test("glob search all types - workspace") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/TestClass.scala
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
           |/a/src/main/scala/com/test/matching/MatchingUtil.scala
           |package com.test.matching
           |
           |object MatchingUtil {
           |  def matchPattern(input: String, pattern: String): Boolean = {
           |    input.matches(pattern)
           |  }
           |}
           |
           |/a/src/main/scala/com/test/NonMatching.scala
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
           |/a/src/main/scala/com/other/OtherPackage.scala
           |package com.other
           |
           |// This should NOT match "matching" queries or "test" queries
           |object OtherUtil {
           |  def otherPattern(): Boolean = true
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/test/TestClass.scala")
      _ = assertNoDiagnostics()
      path = server.toPath("a/src/main/scala/com/test/TestClass.scala")
      // Test searching for "test" - should find packages, classes, objects, trait
      result <- server.headServer.queryEngine.globSearch(
        "test",
        Set.empty,
        path,
      )
      _ = assertNoDiff(
        result.show,
        """|class com.test.TestCaseClass
           |class com.test.TestClass
           |class java.awt.dnd.SerializationTester
           |method com.test.TestClass.testMethod
           |method com.test.TestClass.testValue
           |method com.test.TestObject.testFunction
           |object com.test.TestObject
           |package com.test
           |trait com.test.TestTrait
           |""".stripMargin,
        "query: globSearch(\"test\", Set.empty)",
      )

      // Test searching for "matching" - should find package and object
      matching <- server.headServer.queryEngine.globSearch(
        "matching",
        Set.empty,
        path,
      )
      _ = assertNoDiff(
        matching.show,
        """|class com.test.NonMatchingClass
           |object com.test.matching.MatchingUtil
           |package com.test.matching
           |""".stripMargin,
        "query: globSearch(\"matching\", Set.empty)",
      )

      testClasses <- server.headServer.queryEngine.globSearch(
        "test",
        Set(SymbolType.Class),
        path,
      )
      // Test searching for "test" with class filter
      _ = assertNoDiff(
        testClasses.show,
        """|class com.test.TestCaseClass
           |class com.test.TestClass
           |class java.awt.dnd.SerializationTester
           |package com.test
           |""".stripMargin,
        "query: globSearch(\"test\", Set(SymbolType.Class))",
      )

      methods <- server.headServer.queryEngine
        .globSearch(
          "method",
          Set(SymbolType.Method, SymbolType.Function),
          path,
        )
      // Test searching for methods
      _ = assertNoDiff(
        methods.show,
        """|method com.test.NonMatchingClass.someMethod
           |method com.test.TestClass.testMethod
           |method com.test.TestTrait.abstractMethod
           |""".stripMargin,
        "query: globSearch(\"test\", Set(SymbolType.Class))",
      )
    } yield ()
  }

  test("glob search case insensitive - workspace") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/CaseSensitivity.scala
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
      _ <- server.didOpen("a/src/main/scala/com/test/CaseSensitivity.scala")
      _ = assertNoDiagnostics()
      path = server.toPath("a/src/main/scala/com/test/CaseSensitivity.scala")

      // Case insensitive search for "camel"
      camel <- server.headServer.queryEngine.globSearch(
        "camel",
        Set.empty,
        path,
      )
      _ = assertNoDiff(
        camel.show,
        """|class com.test.CamelCaseClass
           |method com.test.CamelCaseClass.camelCaseMethod
           |""".stripMargin,
      )

      uppercase <- server.headServer.queryEngine.globSearch(
        "uppercase",
        Set.empty,
        path,
      )
      // Case insensitive search for "UPPERCASE"
      _ = assertNoDiff(
        uppercase.show,
        """|class javax.swing.text.MaskFormatter.UpperCaseCharacter
           |method com.test.UPPERCASE_OBJECT.UPPERCASE_METHOD
           |object com.test.UPPERCASE_OBJECT
           |""".stripMargin,
      )

      lowercase <- server.headServer.queryEngine.globSearch(
        "lowercase",
        Set.empty,
        path,
      )
      // Case insensitive search for "lowercase"
      _ = assertNoDiff(
        lowercase.show,
        """|class javax.swing.text.MaskFormatter.LowerCaseCharacter
           |method com.test.lowercase_object.lowercase_method
           |object com.test.lowercase_object
           |""".stripMargin,
      )
    } yield ()
  }

  test("glob search packages - workspace") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/nested/package1/Class1.scala
           |package com.test.nested.package1
           |
           |class Class1
           |
           |/a/src/main/scala/com/test/nested/package2/Class2.scala
           |package com.test.nested.package2
           |
           |class Class2
           |
           |/a/src/main/scala/com/example/ExampleClass.scala
           |package com.example
           |
           |class ExampleClass
           |
           |/a/src/main/scala/com/test/pkgtools/NotAPackage.scala
           |package com.test.pkgtools
           |
           |// Should NOT match "package" query
           |class NotAPackage
           |
           |/a/src/main/scala/org/test/DistantTest.scala
           |package org.test
           |
           |// Should NOT match "com.test" query
           |class DistantTest
           |
           |/a/src/main/scala/com/test/elements/NotNested.scala
           |package com.test.elements
           |
           |// Should NOT match "nested" query
           |class NotNested
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/com/test/nested/package1/Class1.scala"
      )
      _ = assertNoDiagnostics()
      path = server.toPath(
        "a/src/main/scala/com/test/nested/package1/Class1.scala"
      )

      // Search for all packages
      packages <- server.headServer.queryEngine
        .globSearch(
          "package",
          Set(SymbolType.Package),
          path,
        )
      _ = assertNoDiff(
        packages.show,
        """|package com.test.nested.package1
           |package com.test.nested.package2
           |""".stripMargin,
      )

      // Search for test packages
      testPackages <- server.headServer.queryEngine
        .globSearch(
          "test",
          Set(SymbolType.Package),
          path,
        )
      _ = assertNoDiff(
        testPackages.show,
        """|package com.test
           |package org.test
           |""".stripMargin,
      )

      // Search for nested packages
      nestedPackages <- server.headServer.queryEngine
        .globSearch(
          "nested",
          Set(SymbolType.Package),
          path,
        )
      _ = assertNoDiff(
        nestedPackages.show,
        """|package com.test.nested
           |""".stripMargin,
      )
    } yield ()
  }

  test("inspect") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/nested/package1/Class1.scala
           |package com.test.nested.package1
           |
           |class Class1(m: Int) {
           |  def add(x: Int, y: Int): Int = x + y
           |  def add(x: Int): Int = x + m
           |  def substract(x: Int, y: Int): Int = x - y
           |}
           |
           |object Class1 {
           |  def someFunction(x: Int): Int = x * 2
           |}
           |
           |trait Trait1
           |
           |/a/src/main/scala/com/test/nested/package2/Class2.scala
           |package com.test.nested.package2
           |
           |class Class2
           |
           |/a/src/main/scala/com/test/nested/package1/deeper/Class2.scala
           |package com.test.nested.package1.deeper
           |
           |object O
           |/a/src/main/scala/com/test/nested/package2/Nested.scala
           |package com.test.nested.package2
           |
           |class Bar {
           |  object Foo {
           |    class Nested {
           |      def someMethod: Int = 42
           |    }
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/com/test/nested/package2/Class2.scala"
      )
      _ = assertNoDiagnostics()
      path = server.toPath(
        "a/src/main/scala/com/test/nested/package2/Class2.scala"
      )
      res <- server.headServer.queryEngine.inspect(
        "com.test.nested.package1.Class1",
        path,
      )
      _ = assertNoDiff(
        res.show,
        """|class Class1
           |	 - <init>(m: Int): Class1
           |	 - add(x: Int): Int
           |	 - add(x: Int, y: Int): Int
           |	 - substract(x: Int, y: Int): Int
           |object Class1
           |	 - someFunction(x: Int): Int
           |""".stripMargin,
      )
      resPkg <- server.headServer.queryEngine.inspect(
        "com.test.nested.package1",
        path,
      )
      _ = assertNoDiff(
        resPkg.show,
        """|package com.test.nested.package1
           |	 - Class1 com.test.nested.package1
           |	 - Trait1
           |	 - deeper
           |""".stripMargin,
      )
      resMethod <- server.headServer.queryEngine.inspect(
        "com.test.nested.package1.Class1.add",
        path,
      )
      _ = assertNoDiff(
        resMethod.show,
        """|method add(x: Int): Int
           |method add(x: Int, y: Int): Int
           |""".stripMargin,
      )
      resNested <- server.headServer.queryEngine.inspect(
        "com.test.nested.package2.Bar.Foo.Nested",
        path,
      )
      _ = assertNoDiff(
        resNested.show,
        """|class Nested
           |	 - <init>(): mcp0.Foo.Nested
           |	 - someMethod: Int
           |	Given synthetic values for path-dependent types:
           |	 - mcp0: com.test.nested.package2.Bar
           |""".stripMargin,
      )
    } yield ()
  }

  test("inspect-package-object") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/Main.scala
           |package foo
           |
           |package object o {
           |  def someFunction(x: Int): Int = x * 2
           |}
           |/a/src/main/scala/Bar.scala
           |package foo.o
           |
           |object Bar {
           |  def barFunction(x: Int): Int = x * 3
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/Main.scala"
      )
      resPkgObject <- server.headServer.queryEngine.inspect(
        "foo.o",
        server.toPath("a/src/main/scala/Main.scala"),
      )
      _ = assertNoDiff(
        resPkgObject.show,
        """|package foo.o
           |	 - Bar foo.o
           |	 - someFunction(x: Int): Int
           |""".stripMargin,
      )
    } yield ()
  }

  test("inspect-java-generic-type (Issue #7932)") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/Main.scala
           |object Main
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      path = server.toPath("a/src/main/scala/Main.scala")
      result <- server.headServer.queryEngine.inspect(
        "java.util.function.Consumer",
        path,
      )
      _ = assertNoDiff(
        result.show,
        """|trait Consumer
           |	 - accept(x$1: _$1): Unit
           |	 - andThen(x$1: Consumer[_ >: _$1 <: Object]): Consumer[_$1]
           |""".stripMargin,
      )
    } yield ()
  }

  test("docstrings") {
    cleanWorkspace()
    for {
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
      res = server.headServer.queryEngine.getDocumentation(
        "com.test.nested.package1.Class1.add"
      )

      _ = assertNoDiff(
        res.map(_.show).getOrElse(""),
        """|Adds two integers
           |
           |@param x: first argument
           |@param y: second argument
           |
           |@returns sum of x and y
           |""".stripMargin,
      )
    } yield ()
  }

  test("usages") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/Class1.scala
           |package com.test
           |
           |class Class1(m: Int) {
           |  def add(x: Int, y: Int): Int = x + y
           |  def superAdd(x: Int, y: Int): Int = add(x, y) + m
           |}
           |/a/src/main/scala/com/test/Class2.scala
           |package com.test
           |
           |object Class2 {
           |  def foo = new Class1(1).add(2, 3)
           |}
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen(
        "a/src/main/scala/com/test/Class2.scala"
      )
      path = server.toPath(
        "a/src/main/scala/com/test/Class2.scala"
      )
      _ = assertNoDiff(
        server.headServer.queryEngine
          .getUsages("com.test.Class1.add", path)
          .show(server.workspace),
        s"""|${Path.of("a/src/main/scala/com/test/Class1.scala")}:4
            |${Path.of("a/src/main/scala/com/test/Class1.scala")}:5
            |${Path.of("a/src/main/scala/com/test/Class2.scala")}:4
            |""".stripMargin,
      )
    } yield ()
  }

  test("usages-empty-package") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/EmptyPackageClass.scala
           |class EmptyPackageClass(m: Int) {
           |  def add(x: Int, y: Int): Int = x + y
           |  def superAdd(x: Int, y: Int): Int = add(x, y) + m
           |}
           |
           |/a/src/main/scala/EmptyPackageUsage.scala
           |object EmptyPackageUsage {
           |  def foo = new EmptyPackageClass(1).add(2, 3)
           |}
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen(
        "a/src/main/scala/EmptyPackageUsage.scala"
      )
      _ = assertNoDiagnostics()
      path = server.toPath(
        "a/src/main/scala/EmptyPackageUsage.scala"
      )
      _ = assertNoDiff(
        server.headServer.queryEngine
          .getUsages(
            "EmptyPackageClass.add",
            path,
          )
          .show(server.workspace),
        s"""|${Path.of("a/src/main/scala/EmptyPackageClass.scala")}:2
            |${Path.of("a/src/main/scala/EmptyPackageClass.scala")}:3
            |${Path.of("a/src/main/scala/EmptyPackageUsage.scala")}:2
            |""".stripMargin,
      )
    } yield ()
  }

  // Smart fallback tests - no path provided, should auto-detect build target
  test("inspect-smart-fallback") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/MyClass.scala
           |package com.test
           |
           |class MyClass {
           |  def myMethod(x: Int): Int = x * 2
           |}
           |
           |object MyClass {
           |  def apply(): MyClass = new MyClass()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/test/MyClass.scala")
      _ = assertNoDiagnostics()
      // Call inspect without path - should use smart fallback
      res <- server.headServer.queryEngine.inspect(
        "com.test.MyClass",
        path = None,
        module = None,
        searchAllTargets = false,
      )
      _ = assertNoDiff(
        res.results.map(_.show).mkString("\n"),
        """|class MyClass
           |	 - <init>(): MyClass
           |	 - myMethod(x: Int): Int
           |object MyClass
           |	 - apply(): MyClass
           |""".stripMargin,
      )
      // Verify primary target is detected
      _ = assert(
        res.primaryTarget.isDefined,
        "Primary target should be detected via smart fallback",
      )
    } yield ()
  }

  test("docstrings-smart-fallback") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/DocClass.scala
           |package com.test
           |
           |class DocClass {
           |  /**
           |   * Multiplies the input by two.
           |   * @param x the input value
           |   * @return x multiplied by 2
           |   */
           |  def double(x: Int): Int = x * 2
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/test/DocClass.scala")
      _ = assertNoDiagnostics()
      // Call getDocumentation without path - should use smart fallback
      res = server.headServer.queryEngine.getDocumentation(
        "com.test.DocClass.double",
        path = None,
        module = None,
      )
      _ = assertNoDiff(
        res.map(_.show).getOrElse(""),
        """|Multiplies the input by two.
           |
           |@param x: the input value
           |
           |@returns x multiplied by 2
           |""".stripMargin,
      )
    } yield ()
  }

  test("usages-smart-fallback") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/UsageClass.scala
           |package com.test
           |
           |class UsageClass {
           |  def helper(x: Int): Int = x + 1
           |  def useHelper(x: Int): Int = helper(x) + 1
           |}
           |
           |/a/src/main/scala/com/test/UsageConsumer.scala
           |package com.test
           |
           |object UsageConsumer {
           |  def foo = new UsageClass().helper(5)
           |}
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("a/src/main/scala/com/test/UsageClass.scala")
      _ = assertNoDiagnostics()
      // Call getUsages without path - should use smart fallback
      _ = assertNoDiff(
        server.headServer.queryEngine
          .getUsages(
            "com.test.UsageClass.helper",
            path = None,
            module = None,
          )
          .show(server.workspace),
        s"""|${Path.of("a/src/main/scala/com/test/UsageClass.scala")}:4
            |${Path.of("a/src/main/scala/com/test/UsageClass.scala")}:5
            |${Path.of("a/src/main/scala/com/test/UsageConsumer.scala")}:4
            |""".stripMargin,
      )
    } yield ()
  }

  // Test explicit module parameter
  test("inspect-with-module") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/ModuleClass.scala
           |package com.test
           |
           |class ModuleClass {
           |  def moduleMethod(): String = "test"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/test/ModuleClass.scala")
      _ = assertNoDiagnostics()
      // Inspect with explicit module "a"
      res <- server.headServer.queryEngine.inspect(
        "com.test.ModuleClass",
        path = None,
        module = Some("a"),
        searchAllTargets = false,
      )
      _ = assertNoDiff(
        res.results.map(_.show).mkString("\n"),
        """|class ModuleClass
           |	 - <init>(): ModuleClass
           |	 - moduleMethod(): String
           |""".stripMargin,
      )
      _ = assertEquals(res.primaryTarget, Some("a"))
    } yield ()
  }

  test("docstrings-with-module") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": {}
           |}
           |/a/src/main/scala/com/module_a/DocA.scala
           |package com.module_a
           |
           |class DocA {
           |  /**
           |   * Method in module A.
           |   * @return greeting from A
           |   */
           |  def greetA(): String = "Hello from A"
           |}
           |
           |/b/src/main/scala/com/module_b/DocB.scala
           |package com.module_b
           |
           |class DocB {
           |  /**
           |   * Method in module B.
           |   * @return greeting from B
           |   */
           |  def greetB(): String = "Hello from B"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/module_a/DocA.scala")
      _ <- server.didOpen("b/src/main/scala/com/module_b/DocB.scala")
      _ = assertNoDiagnostics()
      // Get docs for DocA using explicit module "a"
      resA = server.headServer.queryEngine.getDocumentation(
        "com.module_a.DocA.greetA",
        path = None,
        module = Some("a"),
      )
      _ = assertNoDiff(
        resA.map(_.show).getOrElse(""),
        """|Method in module A.
           |
           |@returns greeting from A
           |""".stripMargin,
      )
      // Get docs for DocB using explicit module "b"
      resB = server.headServer.queryEngine.getDocumentation(
        "com.module_b.DocB.greetB",
        path = None,
        module = Some("b"),
      )
      _ = assertNoDiff(
        resB.map(_.show).getOrElse(""),
        """|Method in module B.
           |
           |@returns greeting from B
           |""".stripMargin,
      )
    } yield ()
  }

  test("usages-with-module") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {},
           |  "b": {"dependsOn": ["a"]}
           |}
           |/a/src/main/scala/com/shared/SharedUtil.scala
           |package com.shared
           |
           |object SharedUtil {
           |  def sharedMethod(x: Int): Int = x * 2
           |}
           |
           |/a/src/main/scala/com/shared/SharedConsumer.scala
           |package com.shared
           |
           |object SharedConsumer {
           |  def useShared = SharedUtil.sharedMethod(5)
           |}
           |
           |/b/src/main/scala/com/app/AppConsumer.scala
           |package com.app
           |
           |import com.shared.SharedUtil
           |
           |object AppConsumer {
           |  def useFromApp = SharedUtil.sharedMethod(10)
           |}
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("a/src/main/scala/com/shared/SharedUtil.scala")
      _ <- server.didOpen("b/src/main/scala/com/app/AppConsumer.scala")
      _ = assertNoDiagnostics()
      // Get usages with explicit module "a"
      _ = assertNoDiff(
        server.headServer.queryEngine
          .getUsages(
            "com.shared.SharedUtil.sharedMethod",
            path = None,
            module = Some("a"),
          )
          .show(server.workspace),
        s"""|${Path.of("a/src/main/scala/com/shared/SharedConsumer.scala")}:4
            |${Path.of("a/src/main/scala/com/shared/SharedUtil.scala")}:4
            |${Path.of("b/src/main/scala/com/app/AppConsumer.scala")}:6
            |""".stripMargin,
      )
    } yield ()
  }

  test("inspect-searchAllTargets") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/SearchClass.scala
           |package com.test
           |
           |class SearchClass {
           |  def searchMethod(): Int = 42
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/test/SearchClass.scala")
      _ = assertNoDiagnostics()
      // Inspect with searchAllTargets=true (single module)
      res <- server.headServer.queryEngine.inspect(
        "com.test.SearchClass",
        path = None,
        module = None,
        searchAllTargets = true,
      )
      _ = assertNoDiff(
        res.results.map(_.show).mkString("\n"),
        """|class SearchClass
           |	 - <init>(): SearchClass
           |	 - searchMethod(): Int
           |""".stripMargin,
      )
      // Verify at least one target was searched
      _ = assert(
        res.searchedTargets.nonEmpty,
        s"Expected at least 1 searched target, got: ${res.searchedTargets}",
      )
    } yield ()
  }

  test("inspect-invalid-module-fallback") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/test/FallbackClass.scala
           |package com.test
           |
           |class FallbackClass {
           |  def fallbackMethod(): Int = 99
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/test/FallbackClass.scala")
      _ = assertNoDiagnostics()
      // Call with invalid module name - should fall back to smart fallback
      res <- server.headServer.queryEngine.inspect(
        "com.test.FallbackClass",
        path = None,
        module = Some("nonexistent-module"),
        searchAllTargets = false,
      )
      _ = assertNoDiff(
        res.results.map(_.show).mkString("\n"),
        """|class FallbackClass
           |	 - <init>(): FallbackClass
           |	 - fallbackMethod(): Int
           |""".stripMargin,
      )
    } yield ()
  }

  def timed[T](f: => T): T = {
    val start = System.currentTimeMillis()
    val res = f
    val time = System.currentTimeMillis() - start
    scribe.info(s"Time taken: ${time}ms")
    res
  }
}
