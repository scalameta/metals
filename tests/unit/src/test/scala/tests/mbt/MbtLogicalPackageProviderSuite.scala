package tests.mbt

import scala.tools.nsc.ParsedLogicalPackage

import scala.meta.internal.metals.Configs
import scala.meta.internal.metals.mbt.MbtV2WorkspaceSymbolSearch
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolSearch

import munit.AnyFixture
import tests.CustomLoggingFixture
import tests.FileLayout
import tests.TemporaryDirectoryFixture

class MbtLogicalPackageProviderSuite extends munit.FunSuite {
  val workspace = new TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] =
    List(
      workspace,
      CustomLoggingFixture.showWarnings(),
    )

  def newProvider(): MbtWorkspaceSymbolSearch =
    new MbtV2WorkspaceSymbolSearch(
      workspace(),
      config = () => Configs.WorkspaceSymbolProviderConfig.mbt2,
    )(munitExecutionContext)

  def check(layout: String, expected: String): Unit = {
    FileLayout.fromString(layout, root = workspace())
    val provider = newProvider()
    workspace.executeCommand("git init -b main")
    workspace.gitCommitAllChanges()
    provider.onReindex().awaitBackgroundJobs()

    assertNoDiff(
      ParsedLogicalPackage
        .fromMbtIndex(provider.listAllPackages())
        .prettyPrint()
        .stripIndent(),
      expected,
    )
  }

  test("simple") {
    check(
      """|
         |/com/Hello.scala
         |package com.example;
         |object Hello {
         |}
         |""".stripMargin,
      """|com
         |    example
         |        Hello.scala
      """.stripMargin,
    )
  }

  test("package object") {
    check(
      """|
         |/com/package.scala
         |package com;
         |package object hello {
         |  val x = 1
         |}
         |""".stripMargin,
      """|com
         |    hello
         |        package.scala
      """.stripMargin,
    )
  }

  test("multiple files") {
    check(
      """|
         |/com/Hello.scala
         |package com;
         |class Hello {
         |}
         |/com/example/Greeting.scala
         |package com.example;
         |trait Greeting {
         |  def greet(user: User): String = {
         |    "Hello, " + user.name + "!"
         |  }
         |}
         |""".stripMargin,
      """|com
         |    example
         |        Greeting.scala
         |    Hello.scala
      """.stripMargin,
    )
  }

  test("multiple files with package object") {
    check(
      """|
         |/com/package.scala
         |package com;
         |package object hello {
         |  val x = 1
         |}
         |/com/hello/Greeting.scala
         |package com.hello {
         |  trait Greeting
         |}
         |package com.hello.world {
         |  class World
         |}
         |""".stripMargin,
      """|com
         |    hello
         |        world
         |            Greeting.scala
         |        Greeting.scala
         |        package.scala
         |""".stripMargin,
    )
  }

  test("nested packages") {
    check(
      """|
         |/com/hello/world/Greeting.scala
         |package com
         |package hello.world
         |trait Greeting
         |""".stripMargin,
      """|com
         |    hello
         |        world
         |            Greeting.scala
         |""".stripMargin,
    )
  }

  test("mixed Java and Scala files") {
    check(
      """|
         |/com/Hello.java
         |package com;
         |public class Hello {
         |}
         |/com/example/Greeting.scala
         |package com.example;
         |trait Greeting
         |""".stripMargin,
      """|com
         |    example
         |        Greeting.scala
         |    Hello.java
         |""".stripMargin,
    )
  }

  test("java enum") {
    check(
      """|
         |/com/example/Hello.java
         |package com.example;
         |public enum Hello {
         |  A,
         |  B,
         |  C
         |}
         |""".stripMargin,
      """|com
         |    example
         |        Hello.java
         |""".stripMargin,
    )
  }

  test("nested classes") {
    check(
      """|
         |/com/example/Hello.scala
         |package com.example;
         |class Hello {
         |  class World
         |  object World {
         |    class MidEarth
         |  }
         |}
         |""".stripMargin,
      """|com
         |    example
         |        Hello.scala
         |""".stripMargin,
    )
  }

  test("scala package") {
    check(
      """|
         |/scala/Hello.scala
         |package scala;
         |object Hello {
         |}
         |
         |/scala/collection/Hello.scala
         |package scala.collection;
         |object Hello {
         |}
         |""".stripMargin,
      """|scala
         |    collection
         |        Hello.scala
         |""".stripMargin,
    )
  }

  test("unknown files") {
    check(
      """|
         |/com/Hello.proto
         |syntax = "proto3";
         |package com;
         |message Hello {
         |  string name = 1;
         |}
         |/com/Foo.scala
         |package com;
         |class Foo
         |""".stripMargin,
      """|com
         |    Foo.scala
         |""".stripMargin,
    )
  }
}
