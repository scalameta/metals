package tests

import org.eclipse.lsp4j.TypeHierarchyItem

trait TypeHierarchySpec { this: BaseLspSuite =>

  def formatItem(item: TypeHierarchyItem): String = {
    val range = item.getRange
    val selRange = item.getSelectionRange
    val uri = item.getUri
    val workspaceUri = workspace.toURI.toString
    val relativePath = uri.stripPrefix(workspaceUri).stripPrefix("/")

    s"""|${item.getName}
        |  uri: $relativePath
        |  detail: ${item.getDetail}
        |  kind: ${item.getKind}
        |  range: ${range.getStart.getLine}:${range.getStart.getCharacter}-${range.getEnd.getLine}:${range.getEnd.getCharacter}
        |  selectionRange: ${selRange.getStart.getLine}:${selRange.getStart.getCharacter}-${selRange.getEnd.getLine}:${selRange.getEnd.getCharacter}
        |""".stripMargin
  }

  def formatItems(items: List[TypeHierarchyItem]): String =
    items.map(formatItem).sorted.mkString("\n")

  def withMbt: Boolean

  def baseInit: String = if (withMbt) {
    """|/.metals/mbt.json
       |{}
       |""".stripMargin
  } else {
    """|/metals.json
       |{ "a": {} }
       |""".stripMargin
  }
  val objectLine: Int = if (isJava21) 38 else 37

  test("prepare-class") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit            
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
               |  uri: a/src/main/scala/a/Main.scala
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

  testLSP("prepare-from-usage-site") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Animal.scala"
    val b = "b/src/main/scala/b/Main.scala"
    val init = if (withMbt) {
      """|/.metals/mbt.json
         |{}
         |""".stripMargin
    } else {
      """|/metals.json
         |{
         |  "a": {},
         |  "b": {"dependsOn": ["a"]}
         |}
         |""".stripMargin
    }
    for {
      _ <- initialize(
        s"""|$init
            |/$a
            |package a
            |
            |trait Animal
            |class Dog extends Animal
            |class Cat extends Animal
            |/$b
            |package b
            |
            |import a.Dog
            |
            |object Main {
            |  def getDog: Dog = new Dog()
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(b)
      item <- server.prepareTypeHierarchy(
        b,
        "def getDog: Do@@g",
      )
      _ = item match {
        case Some(i) =>
          assertNoDiff(
            formatItem(i),
            """|Dog
               |  uri: a/src/main/scala/a/Animal.scala
               |  detail: a
               |  kind: Class
               |  range: 3:6-3:9
               |  selectionRange: 3:6-3:9
               |""".stripMargin,
          )
        case None =>
          fail("Expected to find type hierarchy item for Dog from usage site")
      }
      subtypes <- server.typeHierarchySubtypes(item.get)
      _ = assertNoDiff(formatItems(subtypes), "")
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        """|Animal
           |  uri: a/src/main/scala/a/Animal.scala
           |  detail: a
           |  kind: Interface
           |  range: 2:6-2:12
           |  selectionRange: 2:6-2:12
           |
           |""".stripMargin,
      )
    } yield ()
  }

  test("supertypes-simple") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
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
            |  uri: a/src/main/scala/a/Main.scala
            |  detail: a
            |  kind: Interface
            |  range: 2:6-2:12
            |  selectionRange: 2:6-2:12
            |
            |""".stripMargin,
      )
    } yield ()
  }

  test("subtypes-simple") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
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
           |  uri: a/src/main/scala/a/Main.scala
           |  detail: a
           |  kind: Class
           |  range: 4:6-4:9
           |  selectionRange: 4:6-4:9
           |
           |Dog
           |  uri: a/src/main/scala/a/Main.scala
           |  detail: a
           |  kind: Class
           |  range: 3:6-3:9
           |  selectionRange: 3:6-3:9
           |""".stripMargin,
      )
    } yield ()
  }

  test("multiple-inheritance") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
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
            |  uri: a/src/main/scala/a/Main.scala
            |  detail: a
            |  kind: Interface
            |  range: 2:6-2:13
            |  selectionRange: 2:6-2:13
            |
            |Swimmable
            |  uri: a/src/main/scala/a/Main.scala
            |  detail: a
            |  kind: Interface
            |  range: 3:6-3:15
            |  selectionRange: 3:6-3:15
            |""".stripMargin,
      )
    } yield ()
  }

  testLSP("cross-project-subtypes") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Service.scala"
    val b = "b/src/main/scala/b/DbService.scala"
    val c = "c/src/main/scala/c/CacheService.scala"
    val init = if (withMbt) {
      """|/.metals/mbt.json
         |{}
         |""".stripMargin
    } else {
      """|/metals.json
         |{
         |  "a": {},
         |  "b": {"dependsOn": ["a"]},
         |  "c": {"dependsOn": ["a"]}
         |}
         |""".stripMargin
    }
    for {
      _ <- initialize(
        s"""
           |$init
           |/$a
           |package a
           |trait Service {
           |  def start(): Unit
           |  def stop(): Unit
           |}
           |/$b
           |package b
           |import a.Service
           |class DbService extends Service {
           |  override def start(): Unit = println("DB started")
           |  override def stop(): Unit = println("DB stopped")
           |}
           |/$c
           |package c
           |import a.Service
           |class CacheService extends Service {
           |  override def start(): Unit = println("Cache started")
           |  override def stop(): Unit = println("Cache stopped")
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      item <- server.prepareTypeHierarchy(
        a,
        "trait Serv@@ice",
      )
      _ = assert(
        item.isDefined,
        "Expected to find type hierarchy item for Service",
      )
      subtypes <- server.typeHierarchySubtypes(item.get)
      _ = assertNoDiff(
        formatItems(subtypes),
        """|CacheService
           |  uri: c/src/main/scala/c/CacheService.scala
           |  detail: c
           |  kind: Class
           |  range: 2:6-2:18
           |  selectionRange: 2:6-2:18
           |
           |DbService
           |  uri: b/src/main/scala/b/DbService.scala
           |  detail: b
           |  kind: Class
           |  range: 2:6-2:15
           |  selectionRange: 2:6-2:15
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("cross-project-supertypes") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Base.scala"
    val b = "b/src/main/scala/b/Derived.scala"
    val init = if (withMbt) {
      """|/.metals/mbt.json
         |{}
         |""".stripMargin
    } else {
      """|/metals.json
         |{
         |  "a": {},
         |  "b": {"dependsOn": ["a"]}
         |}
         |""".stripMargin
    }
    for {
      _ <- initialize(
        s"""
           |$init
           |/$a
           |package a
           |trait Base {
           |  def method(): Unit
           |}
           |/$b
           |package b
           |import a.Base
           |class Derived extends Base {
           |  override def method(): Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(b)
      item <- server.prepareTypeHierarchy(
        b,
        "class Deri@@ved",
      )
      _ = assert(
        item.isDefined,
        "Expected to find type hierarchy item for Derived",
      )
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        """|Base
           |  uri: a/src/main/scala/a/Base.scala
           |  detail: a
           |  kind: Interface
           |  range: 1:6-1:10
           |  selectionRange: 1:6-1:10
           |
           |""".stripMargin,
      )
    } yield ()
  }

  testLSP("multiple-inheritance-supertypes") {
    cleanWorkspace()
    val init = if (withMbt) {
      """|/.metals/mbt.json
         |{}
         |""".stripMargin
    } else {
      """|/metals.json
         |{ "a": {} }
         |""".stripMargin
    }
    for {
      _ <- initialize(
        s"""|$init
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
            |  uri: a/src/main/scala/a/Main.scala
            |  detail: a
            |  kind: Interface
            |  range: 2:6-2:13
            |  selectionRange: 2:6-2:13
            |
            |Swimmable
            |  uri: a/src/main/scala/a/Main.scala
            |  detail: a
            |  kind: Interface
            |  range: 3:6-3:15
            |  selectionRange: 3:6-3:15
            |""".stripMargin,
      )
    } yield ()
  }

  testLSP("deep-hierarchy-subtypes") {
    cleanWorkspace()
    val a = "a/src/main/scala/a/Animal.scala"
    val b = "a/src/main/scala/a/Mammal.scala"
    val c = "a/src/main/scala/a/Dog.scala"
    val init = if (withMbt) {
      """|/.metals/mbt.json
         |{}
         |""".stripMargin
    } else {
      """|/metals.json
         |{ 
         |  "a": {},
         |  "b": {"dependsOn": ["a"]},
         |  "c": {"dependsOn": ["b"]}
         |}
         |""".stripMargin
    }
    for {
      _ <- initialize(
        s"""
           |$init
           |/$a
           |package a
           |trait Animal
           |/$b
           |package a
           |trait Mammal extends Animal
           |/$c
           |package a
           |class Dog extends Mammal
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      item <- server.prepareTypeHierarchy(
        a,
        "trait Ani@@mal",
      )
      _ = assert(
        item.isDefined,
        "Expected to find type hierarchy item for Animal",
      )
      subtypes <- server.typeHierarchySubtypes(item.get)
      _ = assertNoDiff(
        formatItems(subtypes),
        """|  
           |Dog
           |  uri: a/src/main/scala/a/Dog.scala
           |  detail: a
           |  kind: Class
           |  range: 1:6-1:9
           |  selectionRange: 1:6-1:9
           |
           |Mammal
           |  uri: a/src/main/scala/a/Mammal.scala
           |  detail: a
           |  kind: Interface
           |  range: 1:6-1:12
           |  selectionRange: 1:6-1:12
           |""".stripMargin,
      )
    } yield ()
  }

  test("java-prepare-class") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
            |/a/src/main/java/a/Animal.java
            |package a;
            |
            |public interface Animal {
            |  void speak();
            |}
            |/a/src/main/java/a/Dog.java
            |package a;
            |
            |public class Dog implements Animal {
            |  public void speak() {
            |    System.out.println("Woof!");
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Dog.java")
      item <- server.prepareTypeHierarchy(
        "a/src/main/java/a/Dog.java",
        "public class Do@@g",
      )
      _ = item match {
        case Some(i) =>
          assertNoDiff(
            formatItem(i),
            """|Dog
               |  uri: a/src/main/java/a/Dog.java
               |  detail: a
               |  kind: Class
               |  range: 2:13-2:16
               |  selectionRange: 2:13-2:16
               |""".stripMargin,
          )
        case None => fail("Expected to find type hierarchy item for Dog")
      }
    } yield ()
  }

  testLSP("java-prepare-from-usage-site") {
    cleanWorkspace()
    val animal = "a/src/main/java/a/Animal.java"
    val context = "a/src/main/java/a/Context.java"
    for {
      _ <- initialize(
        s"""|$baseInit
            |/$animal
            |package a;
            |
            |public interface Animal {
            |  void speak();
            |}
            |/$context
            |package a;
            |
            |public class Context {
            |  public Animal getAnimal() {
            |    return null;
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen(context)
      item <- server.prepareTypeHierarchy(
        context,
        "public Ani@@mal getAnimal",
      )
      _ = item match {
        case Some(i) =>
          assertNoDiff(
            formatItem(i),
            """|Animal
               |  uri: a/src/main/java/a/Animal.java
               |  detail: a
               |  kind: Interface
               |  range: 2:17-2:23
               |  selectionRange: 2:17-2:23
               |""".stripMargin,
          )
        case None =>
          fail(
            "Expected to find type hierarchy item for Animal from usage site"
          )
      }
    } yield ()
  }

  test("java-supertypes-interface") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
            |/a/src/main/java/a/Animal.java
            |package a;
            |
            |public interface Animal {
            |  void speak();
            |}
            |/a/src/main/java/a/Dog.java
            |package a;
            |
            |public class Dog implements Animal {
            |  public void speak() {
            |    System.out.println("Woof!");
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Dog.java")
      item <- server.prepareTypeHierarchy(
        "a/src/main/java/a/Dog.java",
        "public class Do@@g",
      )
      _ = assert(item.isDefined)
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        s"""|Animal
            |  uri: a/src/main/java/a/Animal.java
            |  detail: a
            |  kind: Interface
            |  range: 2:17-2:23
            |  selectionRange: 2:17-2:23
            |
            |Object
            |  uri: .metals/readonly/dependencies/src.zip/java.base/java/lang/Object.java
            |  detail: java.lang
            |  kind: Class
            |  range: $objectLine:13-$objectLine:19
            |  selectionRange: $objectLine:13-$objectLine:19
            |
            |""".stripMargin,
      )
    } yield ()
  }

  test("java-multiple-interfaces") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
            |/a/src/main/java/a/Flyable.java
            |package a;
            |
            |public interface Flyable {
            |  void fly();
            |}
            |/a/src/main/java/a/Swimmable.java
            |package a;
            |
            |public interface Swimmable {
            |  void swim();
            |}
            |/a/src/main/java/a/Duck.java
            |package a;
            |
            |public class Duck implements Flyable, Swimmable {
            |  public void fly() {}
            |  public void swim() {}
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Duck.java")
      item <- server.prepareTypeHierarchy(
        "a/src/main/java/a/Duck.java",
        "public class Du@@ck",
      )
      _ = assert(item.isDefined)
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        s"""|Flyable
            |  uri: a/src/main/java/a/Flyable.java
            |  detail: a
            |  kind: Interface
            |  range: 2:17-2:24
            |  selectionRange: 2:17-2:24
            |
            |Object
            |  uri: .metals/readonly/dependencies/src.zip/java.base/java/lang/Object.java
            |  detail: java.lang
            |  kind: Class
            |  range: $objectLine:13-$objectLine:19
            |  selectionRange: $objectLine:13-$objectLine:19
            |
            |Swimmable
            |  uri: a/src/main/java/a/Swimmable.java
            |  detail: a
            |  kind: Interface
            |  range: 2:17-2:26
            |  selectionRange: 2:17-2:26
            |""".stripMargin,
      )
    } yield ()
  }

  testLSP("java-scala-interop-supertypes") {
    cleanWorkspace()
    val javaFile = "a/src/main/java/a/Base.java"
    val scalaFile = "a/src/main/scala/a/ScalaImpl.scala"
    for {
      _ <- initialize(
        s"""
           |$baseInit
           |/$javaFile
           |package a;
           |
           |public interface Base {
           |  void method();
           |}
           |/$scalaFile
           |package a
           |
           |class ScalaImpl extends Base {
           |  override def method(): Unit = ()
           |}
           |""".stripMargin
      )
      _ <- server.didOpenAndFocus(scalaFile)
      item <- server.prepareTypeHierarchy(
        scalaFile,
        "class Scala@@Impl",
      )
      _ = assert(
        item.isDefined,
        "Expected to find type hierarchy item for ScalaImpl",
      )
      supertypes <- server.typeHierarchySupertypes(item.get)
      _ = assertNoDiff(
        formatItems(supertypes),
        s"""|Base
            |  uri: a/src/main/java/a/Base.java
            |  detail: a
            |  kind: Interface
            |  range: 2:17-2:21
            |  selectionRange: 2:17-2:21
            |
            |Object
            |  uri: .metals/readonly/dependencies/src.zip/java.base/java/lang/Object.java
            |  detail: java.lang
            |  kind: Class
            |  range: $objectLine:13-$objectLine:19
            |  selectionRange: $objectLine:13-$objectLine:19
            |
            |""".stripMargin,
      )
    } yield ()
  }

  test("java-interface-subtypes") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|$baseInit
            |/a/src/main/java/a/Animal.java
            |package a;
            |
            |public interface Animal {
            |  void speak();
            |}
            |/a/src/main/java/a/Dog.java
            |package a;
            |
            |public class Dog implements Animal {
            |  public void speak() {
            |    System.out.println("Woof!");
            |  }
            |}
            |/a/src/main/java/a/Cat.java
            |package a;
            |
            |public class Cat implements Animal {
            |  public void speak() {
            |    System.out.println("Meow!");
            |  }
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/java/a/Animal.java")
      item <- server.prepareTypeHierarchy(
        "a/src/main/java/a/Animal.java",
        "public interface Ani@@mal",
      )
      _ = assert(
        item.isDefined,
        "Expected to find type hierarchy item for Animal interface",
      )
      subtypes <- server.typeHierarchySubtypes(item.get)
      _ = assertNoDiff(
        formatItems(subtypes),
        """|Cat
           |  uri: a/src/main/java/a/Cat.java
           |  detail: a
           |  kind: Class
           |  range: 2:13-2:16
           |  selectionRange: 2:13-2:16
           |
           |Dog
           |  uri: a/src/main/java/a/Dog.java
           |  detail: a
           |  kind: Class
           |  range: 2:13-2:16
           |  selectionRange: 2:13-2:16
           |""".stripMargin,
      )
    } yield ()
  }
}
