package tests

import scala.meta.internal.metals.InitializationOptions

import org.eclipse.lsp4j.TypeHierarchyItem

class TypeHierarchyLspSuite extends BaseLspSuite("type-hierarchy") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  private def formatItem(item: TypeHierarchyItem): String = {
    val range = item.getRange
    val selRange = item.getSelectionRange
    s"""|${item.getName}
        |  detail: ${item.getDetail}
        |  kind: ${item.getKind}
        |  range: ${range.getStart.getLine}:${range.getStart.getCharacter}-${range.getEnd.getLine}:${range.getEnd.getCharacter}
        |  selectionRange: ${selRange.getStart.getLine}:${selRange.getStart.getCharacter}-${selRange.getEnd.getLine}:${selRange.getEnd.getCharacter}
        |""".stripMargin
  }

  private def formatItems(items: List[TypeHierarchyItem]): String =
    items.map(formatItem).sorted.mkString("\n")

  private def objectLine: Int =
    if (isJava21) 38
    else 37

  test("prepare-class") {
    for {
      _ <- initialize(
        """|/metals.json
           |{ "a": {} }
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |trait Animal
           |class Dog extends Animal
           |class Cat extends Animal
           |
           |object Main {
           |  val d: Dog = new Dog()
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      item <- server.prepareTypeHierarchy(
        "a/src/main/scala/a/Main.scala",
        "class Do@@g",
      )
      _ = item match {
        case Some(i) =>
          assertNoDiff(
            formatItem(i),
            """|Dog
               |  detail: a
               |  kind: Class
               |  range: 3:6-3:9
               |  selectionRange: 3:6-3:9
               |""".stripMargin,
          )
        case None => fail("Expected to find type hierarchy item for Dog")
      }
    } yield ()
  }

  test("supertypes-simple") {
    for {
      _ <- initialize(
        """|/metals.json
           |{ "a": {} }
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |trait Animal
           |class Dog extends Animal
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      item <- server.prepareTypeHierarchy(
        "a/src/main/scala/a/Main.scala",
        "class Do@@g",
      )
      _ = assert(item.isDefined)
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        s"""|Animal
            |  detail: a
            |  kind: Interface
            |  range: 2:6-2:12
            |  selectionRange: 2:6-2:12
            |
            |Object
            |  detail: java.lang
            |  kind: Class
            |  range: $objectLine:13-$objectLine:19
            |  selectionRange: $objectLine:13-$objectLine:19
            |""".stripMargin,
      )
    } yield ()
  }

  test("subtypes-simple") {
    for {
      _ <- initialize(
        """|/metals.json
           |{ "a": {} }
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |trait Animal
           |class Dog extends Animal
           |class Cat extends Animal
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      item <- server.prepareTypeHierarchy(
        "a/src/main/scala/a/Main.scala",
        "trait Ani@@mal",
      )
      _ = assert(item.isDefined)
      subtypes <- server.typeHierarchySubtypes(item.get)
      _ = assertNoDiff(
        formatItems(subtypes),
        """|Cat
           |  detail: a
           |  kind: Class
           |  range: 4:6-4:9
           |  selectionRange: 4:6-4:9
           |
           |Dog
           |  detail: a
           |  kind: Class
           |  range: 3:6-3:9
           |  selectionRange: 3:6-3:9
           |""".stripMargin,
      )
    } yield ()
  }

  test("multiple-inheritance") {
    for {
      _ <- initialize(
        """|/metals.json
           |{ "a": {} }
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |trait Flyable
           |trait Swimmable
           |class Duck extends Flyable with Swimmable
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      item <- server.prepareTypeHierarchy(
        "a/src/main/scala/a/Main.scala",
        "class Du@@ck",
      )
      _ = assert(item.isDefined)
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        s"""|Flyable
            |  detail: a
            |  kind: Interface
            |  range: 2:6-2:13
            |  selectionRange: 2:6-2:13
            |
            |Object
            |  detail: java.lang
            |  kind: Class
            |  range: $objectLine:13-$objectLine:19
            |  selectionRange: $objectLine:13-$objectLine:19
            |
            |Swimmable
            |  detail: a
            |  kind: Interface
            |  range: 3:6-3:15
            |  selectionRange: 3:6-3:15
            |""".stripMargin,
      )
    } yield ()
  }
}
