package tests

class DocumentLinkLspSuite extends BaseLspSuite("document-link") {

  test("java-link-tag-resolve") {
    val file = "a/src/main/java/a/Main.java"
    val targetFile = "a/src/main/java/a/Helper.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Helper.java
           |package a;
           |
           |public class Helper {
           |  public static void doSomething() {}
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |import java.util.Optional;
           |
           |/**
           | * Uses {@link a.Helper} for assistance.
           | * @see a.Helper#doSomething
           | * {@link Optional#empty}
           | */
           |public class Main {
           |  public static void main(String[] args) {
           |    Helper.doSomething();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen(targetFile)
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 3)
      linkTag = links.find(_.getTooltip == "a.Helper").get
      seeTag = links.find(_.getTooltip == "a.Helper#doSomething").get
      linkTag2 = links.find(_.getTooltip == "Optional#empty").get
      resolvedLinkTag <- server.documentLinkResolve(linkTag)
      resolvedSeeTag <- server.documentLinkResolve(seeTag)
      resolvedSeeTag2 <- server.documentLinkResolve(linkTag2)
    } yield {
      assert(
        resolvedLinkTag.getTarget != null,
        "resolved link tag target should not be null",
      )
      assert(
        resolvedLinkTag.getTarget.contains("Helper.java"),
        s"link tag should resolve to Helper.java but got: ${resolvedLinkTag.getTarget}",
      )
      assert(
        resolvedSeeTag.getTarget != null,
        "resolved see tag target should not be null",
      )
      assert(
        resolvedSeeTag.getTarget.contains("Helper.java"),
        s"see tag should resolve to Helper.java but got: ${resolvedSeeTag.getTarget}",
      )
      assert(
        resolvedSeeTag2.getTarget.contains("Optional.java"),
        s"see tag should resolve to Optional.java but got: ${resolvedSeeTag.getTarget}",
      )
    }
  }

  test("java-link-to-jdk-class") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |import java.util.List;
           |
           |/**
           | * Uses {@link java.util.List} for collections.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 1)
      link = links.head
      _ = assertEquals(link.getTooltip, "java.util.List")
      resolved <- server.documentLinkResolve(link)
    } yield {
      assert(
        resolved.getTarget == null || resolved.getTarget.contains("List"),
        s"link should resolve to List or be null (JDK sources not available), got: ${resolved.getTarget}",
      )
    }
  }

  test("java-url-links-unchanged") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * See https://example.com for more info.
           | */
           |public class Main {}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 1)
      link = links.head
      _ = assertEquals(link.getTarget, "https://example.com")
      resolved <- server.documentLinkResolve(link)
    } yield {
      assertEquals(resolved.getTarget, "https://example.com")
    }
  }

  test("java-local-method-reference") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link #helper} for assistance.
           | */
           |public class Main {
           |  public void helper() {}
           |
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 1)
      link = links.head
      _ = assertEquals(link.getTooltip, "#helper")
      resolved <- server.documentLinkResolve(link)
    } yield {
      assert(
        resolved.getTarget != null,
        "resolved local method reference target should not be null",
      )
      assert(
        resolved.getTarget.contains("Main.java"),
        s"local method ref should resolve to Main.java but got: ${resolved.getTarget}",
      )
    }
  }

  test("java-multiple-links") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Foo.java
           |package a;
           |public class Foo {}
           |/a/src/main/java/a/Bar.java
           |package a;
           |public class Bar {}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link a.Foo} and {@link a.Bar}.
           | * @see a.Foo
           | * @see https://example.com
           | */
           |public class Main {}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/a/Foo.java")
      _ <- server.didOpen("a/src/main/java/a/Bar.java")
      links <- server.documentLinks(file)
    } yield {
      assertEquals(links.length, 4)
      val tooltips = links.map(_.getTooltip).toSet
      assert(tooltips.contains("a.Foo"), "should have a.Foo link")
      assert(tooltips.contains("a.Bar"), "should have a.Bar link")
      assert(tooltips.contains("https://example.com"), "should have URL link")
    }
  }

  test("java-same-package-simple-name") {
    val file = "a/src/main/java/com/example/Main.java"
    val targetFile = "a/src/main/java/com/example/Helper.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/com/example/Helper.java
           |package com.example;
           |
           |public class Helper {
           |  public static void doSomething() {}
           |}
           |/a/src/main/java/com/example/Main.java
           |package com.example;
           |
           |/**
           | * Uses {@link Helper} for assistance.
           | * @see Helper#doSomething
           | */
           |public class Main {
           |  public static void main(String[] args) {
           |    Helper.doSomething();
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen(targetFile)
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 2)
      linkTag = links.find(_.getTooltip == "Helper").get
      seeTag = links.find(_.getTooltip == "Helper#doSomething").get
      resolvedLinkTag <- server.documentLinkResolve(linkTag)
      resolvedSeeTag <- server.documentLinkResolve(seeTag)
    } yield {
      assert(
        resolvedLinkTag.getTarget != null,
        "resolved simple class name link should not be null",
      )
      assert(
        resolvedLinkTag.getTarget.contains("Helper.java"),
        s"simple class name should resolve to Helper.java but got: ${resolvedLinkTag.getTarget}",
      )
      assert(
        resolvedSeeTag.getTarget != null,
        "resolved simple method reference should not be null",
      )
      assert(
        resolvedSeeTag.getTarget.contains("Helper.java"),
        s"simple method ref should resolve to Helper.java but got: ${resolvedSeeTag.getTarget}",
      )
    }
  }

  test("java-different-package-with-import") {
    val file = "a/src/main/java/com/example/Main.java"
    val targetFile = "a/src/main/java/com/other/OtherClass.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/com/other/OtherClass.java
           |package com.other;
           |
           |public class OtherClass {
           |  public static void doSomething() {}
           |}
           |/a/src/main/java/com/example/Main.java
           |package com.example;
           |
           |import com.other.OtherClass;
           |
           |/**
           | * Uses {@link OtherClass} from a different package.
           | */
           |public class Main {}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen(targetFile)
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 1)
      link = links.head
      _ = assertEquals(link.getTooltip, "OtherClass")
      resolved <- server.documentLinkResolve(link)
    } yield {
      assert(
        resolved.getTarget != null,
        "resolved different package class should not be null (found via import)",
      )
      assert(
        resolved.getTarget.contains("OtherClass.java"),
        s"different package class should resolve to OtherClass.java but got: ${resolved.getTarget}",
      )
    }
  }
}
