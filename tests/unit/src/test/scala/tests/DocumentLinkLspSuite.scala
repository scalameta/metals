package tests

import scala.concurrent.Future

import munit.Location
import org.eclipse.lsp4j.DocumentLink

class DocumentLinkLspSuite extends BaseLspSuite("document-link") {

  private def linkWithTooltip(
      links: List[DocumentLink],
      tooltip: String,
  )(implicit loc: Location): DocumentLink =
    links
      .find(_.getTooltip == tooltip)
      .getOrElse(
        fail(s"no link '$tooltip', got: ${links.map(_.getTooltip)}")
      )

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
      linkTag = linkWithTooltip(links, "a.Helper")
      seeTag = linkWithTooltip(links, "a.Helper#doSomething")
      linkTag2 = linkWithTooltip(links, "Optional#empty")
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
      linkTag = linkWithTooltip(links, "Helper")
      seeTag = linkWithTooltip(links, "Helper#doSomething")
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

  // `Outer.Inner` is dotted but names no package, so for a file in `package a`
  // it means `a/Outer#Inner#`.
  test("java-link-to-nested-class-same-package") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Outer.java
           |package a;
           |
           |public class Outer {
           |  public static class Inner {
           |    public static void doSomething() {}
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link Outer.Inner} and {@link Outer.Inner#doSomething}.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/a/Outer.java")
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 2)
      classLink = linkWithTooltip(links, "Outer.Inner")
      memberLink = linkWithTooltip(links, "Outer.Inner#doSomething")
      resolvedClass <- server.documentLinkResolve(classLink)
      resolvedMember <- server.documentLinkResolve(memberLink)
    } yield {
      assert(
        resolvedClass.getTarget != null,
        "same-package nested class link should resolve",
      )
      assert(
        resolvedClass.getTarget.contains("Outer.java"),
        s"same-package nested class link should resolve to Outer.java but got: ${resolvedClass.getTarget}",
      )
      assert(
        resolvedMember.getTarget != null,
        "same-package nested class member link should resolve",
      )
      assert(
        resolvedMember.getTarget.contains("Outer.java"),
        s"same-package nested class member link should resolve to Outer.java but got: ${resolvedMember.getTarget}",
      )
    }
  }

  /**
   * Checks that references of one kind, `42` or `e.g.`, produce links with no
   * definition behind them.
   */
  private def checkNoDefinition(
      name: String,
      references: List[String],
  ): Unit =
    test(name) {
      val file = "a/src/main/java/a/Main.java"
      val referenceLinks =
        references.map(reference => s"{@link $reference}").mkString(", ")
      for {
        _ <- initialize(
          s"""|/metals.json
              |{"a":{}}
              |/a/src/main/java/a/Outer.java
              |package a;
              |
              |public class Outer {}
              |/a/src/main/java/a/Main.java
              |package a;
              |
              |/**
              | * Uses $referenceLinks.
              | */
              |public class Main {
              |  public static void main(String[] args) {}
              |}
              |""".stripMargin
        )
        _ <- server.didOpen(file)
        _ <- server.didOpen("a/src/main/java/a/Outer.java")
        links <- server.documentLinks(file)
        _ = assertEquals(
          obtained = links.map(_.getTooltip),
          expected = references,
        )
        resolved <- Future.sequence(links.map(server.documentLinkResolve))
      } yield assertEquals(
        obtained = resolved.filter(_.getTarget != null).map(_.getTooltip),
        expected = List.empty[String],
        clue = "a reference that names no class must not resolve",
      )
    }

  checkNoDefinition(
    name = "java-link-to-package-name",
    references = List("a"),
  )
  checkNoDefinition(
    name = "java-link-to-parameterized-type",
    references = List("Outer<Foo,Bar>"),
  )
  checkNoDefinition(
    name = "java-link-to-abbreviation",
    references = List("e.g."),
  )
  checkNoDefinition(
    name = "java-link-to-member-of-nothing",
    references = List("#"),
  )
  checkNoDefinition(
    name = "java-link-to-number",
    references = List("42", "3.14", "0x1F"),
  )
  // `true` and `null` read as identifiers, so they are looked up like a name
  // and find nothing.
  checkNoDefinition(
    name = "java-link-to-boolean",
    references = List("true", "false"),
  )
  checkNoDefinition(
    name = "java-link-to-null-literal",
    references = List("null"),
  )

  // `com.example` is the shape closest to a qualified class,
  // `com.example.Outer`. The current package is a prefix of two parts too.
  test("java-link-to-multi-part-package-name") {
    val file = "a/src/main/java/com/example/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/com/example/Outer.java
           |package com.example;
           |
           |public class Outer {}
           |/a/src/main/java/com/example/Main.java
           |package com.example;
           |
           |/**
           | * Uses {@link com.example}.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/com/example/Outer.java")
      links <- server.documentLinks(file)
      _ = assertEquals(
        obtained = links.map(_.getTooltip),
        expected = List("com.example"),
      )
      resolved <- server.documentLinkResolve(links.head)
    } yield assert(
      cond = resolved.getTarget == null,
      clue =
        s"a package names no class, so its link must not resolve, but got: ${resolved.getTarget}",
    )
  }

  // A comment mixes references that name something with references that don't.
  // A neighbour must not change what a link resolves to.
  test("java-link-to-valid-and-invalid-references") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Outer.java
           |package a;
           |
           |public class Outer {
           |  public static class Inner {
           |    public static void doSomething() {}
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link Outer}, {@link 42}, {@link Outer.Inner}, {@link a},
           | * {@link Outer.Inner#doSomething} and {@link Outer<Foo>}.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/a/Outer.java")
      links <- server.documentLinks(file)
      _ = assertEquals(obtained = links.length, expected = 6)
      resolved <- Future.sequence(links.map(server.documentLinkResolve))
    } yield {
      val (withTarget, withoutTarget) =
        resolved.partition(_.getTarget != null)
      assertEquals(
        obtained = withTarget.map(_.getTooltip).sorted,
        expected = List("Outer", "Outer.Inner", "Outer.Inner#doSomething"),
      )
      assertEquals(
        obtained = withoutTarget.map(_.getTooltip).sorted,
        expected = List("42", "Outer<Foo>", "a"),
      )
      val elsewhere =
        withTarget.filterNot(_.getTarget.contains("Outer.java"))
      assertEquals(
        obtained = elsewhere.map(_.getTarget),
        expected = List.empty[String],
        clue = "a reference that resolves names something in Outer.java",
      )
    }
  }

  // Convention reads the lowercase `outer` as a package, so `a/outer#Inner#`
  // is the last reading tried. A class is free to be lowercase, so the link
  // still has to resolve.
  test("java-link-to-nested-class-of-lowercase-class") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/outer.java
           |package a;
           |
           |public class outer {
           |  public static class Inner {
           |    public static void doSomething() {}
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link outer.Inner} and {@link outer.Inner#doSomething}.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/a/outer.java")
      links <- server.documentLinks(file)
      _ = assertEquals(obtained = links.length, expected = 2)
      classLink = linkWithTooltip(links, "outer.Inner")
      memberLink = linkWithTooltip(links, "outer.Inner#doSomething")
      resolvedClass <- server.documentLinkResolve(classLink)
      resolvedMember <- server.documentLinkResolve(memberLink)
    } yield {
      assert(
        cond = resolvedClass.getTarget != null,
        clue = "lowercase class nested class link should resolve",
      )
      assert(
        cond = resolvedClass.getTarget.contains("outer.java"),
        clue =
          s"lowercase class nested class link should resolve to outer.java but got: ${resolvedClass.getTarget}",
      )
      assert(
        cond = resolvedMember.getTarget != null,
        clue = "lowercase class nested class member link should resolve",
      )
      assert(
        cond = resolvedMember.getTarget.contains("outer.java"),
        clue =
          s"lowercase class nested class member link should resolve to outer.java but got: ${resolvedMember.getTarget}",
      )
    }
  }

  // With no capitalized part, `outer.inner` reads as class `inner` of package
  // `outer`, so `a/outer#inner#` is the last of the four readings.
  test("java-link-to-lowercase-nested-class-of-lowercase-class") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/outer.java
           |package a;
           |
           |public class outer {
           |  public static class inner {
           |    public static void doSomething() {}
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link outer.inner} and {@link outer.inner#doSomething}.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/a/outer.java")
      links <- server.documentLinks(file)
      _ = assertEquals(obtained = links.length, expected = 2)
      classLink = linkWithTooltip(links, "outer.inner")
      memberLink = linkWithTooltip(links, "outer.inner#doSomething")
      resolvedClass <- server.documentLinkResolve(classLink)
      resolvedMember <- server.documentLinkResolve(memberLink)
    } yield {
      assert(
        cond = resolvedClass.getTarget != null,
        clue = "all-lowercase nested class link should resolve",
      )
      assert(
        cond = resolvedClass.getTarget.contains("outer.java"),
        clue =
          s"all-lowercase nested class link should resolve to outer.java but got: ${resolvedClass.getTarget}",
      )
      assert(
        cond = resolvedMember.getTarget != null,
        clue = "all-lowercase nested class member link should resolve",
      )
      assert(
        cond = resolvedMember.getTarget.contains("outer.java"),
        clue =
          s"all-lowercase nested class member link should resolve to outer.java but got: ${resolvedMember.getTarget}",
      )
    }
  }

  // Javadoc separates a nested class from its outer one with a dot, the same
  // character that separates packages, so `a.Outer.Inner` has to be tried as
  // `a/Outer#Inner#` and not only as `a/Outer/Inner#`.
  test("java-link-to-nested-class") {
    val file = "a/src/main/java/a/Main.java"
    for {
      _ <- initialize(
        """|/metals.json
           |{"a":{}}
           |/a/src/main/java/a/Outer.java
           |package a;
           |
           |public class Outer {
           |  public static class Inner {
           |    public static void doSomething() {}
           |  }
           |}
           |/a/src/main/java/a/Main.java
           |package a;
           |
           |/**
           | * Uses {@link a.Outer.Inner} and {@link a.Outer.Inner#doSomething}.
           | */
           |public class Main {
           |  public static void main(String[] args) {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(file)
      _ <- server.didOpen("a/src/main/java/a/Outer.java")
      links <- server.documentLinks(file)
      _ = assertEquals(links.length, 2)
      classLink = linkWithTooltip(links, "a.Outer.Inner")
      memberLink = linkWithTooltip(links, "a.Outer.Inner#doSomething")
      resolvedClass <- server.documentLinkResolve(classLink)
      resolvedMember <- server.documentLinkResolve(memberLink)
    } yield {
      assert(
        resolvedClass.getTarget != null,
        "nested class link should resolve",
      )
      assert(
        resolvedClass.getTarget.contains("Outer.java"),
        s"nested class link should resolve to Outer.java but got: ${resolvedClass.getTarget}",
      )
      assert(
        resolvedMember.getTarget != null,
        "nested class member link should resolve",
      )
      assert(
        resolvedMember.getTarget.contains("Outer.java"),
        s"nested class member link should resolve to Outer.java but got: ${resolvedMember.getTarget}",
      )
    }
  }
}
