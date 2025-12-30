package tests

class TypeHierarchyLspSuite extends BaseLspSuite("type-hierarchy") {
  test("prepare-class") {
    for {
      _ <- initialize(
        """|/a/src/main/scala/a/Main.scala
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
        case Some(i) => assertEquals(i.getName, "Dog")
        case None => fail("Expected to find type hierarchy item for Dog")
      }
    } yield ()
  }

  test("supertypes-simple") {
    for {
      _ <- initialize(
        """|/a/src/main/scala/a/Main.scala
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
      _ = assertEquals(supertypes.map(_.getName).toSet, Set("Animal", "Object"))
    } yield ()
  }

  test("subtypes-simple") {
    for {
      _ <- initialize(
        """|/a/src/main/scala/a/Main.scala
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
      _ = assertEquals(subtypes.map(_.getName).toSet, Set("Dog", "Cat"))
    } yield ()
  }

  test("multiple-inheritance") {
    for {
      _ <- initialize(
        """|/a/src/main/scala/a/Main.scala
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
      _ = assertEquals(
        supertypes.map(_.getName).toSet,
        Set("Flyable", "Swimmable", "Object"),
      )
    } yield ()
  }
}
