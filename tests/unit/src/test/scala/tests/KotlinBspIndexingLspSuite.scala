package tests

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments.*
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig
import scala.meta.internal.mtags.Symbol

/** Startup indexing of build target sources, used by the BSP symbol provider. */
class KotlinBspIndexingLspSuite extends BaseLspSuite("kotlin-bsp-indexing") {
  override def userConfig: UserConfiguration = super.userConfig.copy(
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.BSP
  )

  private var definitionIndexStrategy = "classpath"
  // The server reads the initialization options in `super.beforeEach`.
  override def beforeEach(context: BeforeEach): Unit = {
    definitionIndexStrategy =
      if (context.test.name.endsWith("-sources")) "sources" else "classpath"
    super.beforeEach(context)
  }
  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      TestingServer.TestDefault.copy(
        definitionIndexStrategy = Some(definitionIndexStrategy)
      )
    )

  test("kotlin-source-indexed-at-startup") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Account.kt
           |package a
           |
           |class Account(val owner: String) {
           |  fun deposit(amount: Long) {}
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(
        server
          .workspaceSymbol("deposit")
          .linesIterator
          .filterNot(_.startsWith("Add ';'")) // shown on some runs only
          .mkString("\n"),
        "a.Account#deposit",
      )
    } yield ()
  }

  test("kotlin-facade-in-definition-index-only") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Account.kt
           |package a
           |
           |fun openAccount(owner: String) = owner
           |""".stripMargin
      )
      _ = assertEquals(
        server.server.definitionIndex
          .definition(Symbol("a/AccountKt#openAccount()."))
          .map(_.definitionSymbol.value),
        Some("a/AccountKt#openAccount()."),
      )
      _ = assertEquals(
        server.server.definitionIndex
          .definition(Symbol("a/openAccount()."))
          .map(d => d.definitionSymbol.value -> d.path.filename),
        Some("a/openAccount()." -> "Account.kt"),
      )
      _ = assertNoDiff(
        server
          .workspaceSymbol("Account")
          .linesIterator
          .filterNot(_.startsWith("Add ';'")) // shown on some runs only
          .mkString("\n"),
        "a.openAccount",
      )
    } yield ()
  }

  // Java and Scala call Kotlin top-level functions through their JVM
  // classes. The multifile facade `CollectionsKt` inherits each `listOf` from
  // the part class of the file that declares it. Scala 2 sees a static member
  // only in the class that declares it, and the part classes are
  // package-private, so only Java calls `listOf`.
  for (strategy <- List("classpath", "sources"))
    test(s"java-and-scala-to-kotlin-library-$strategy") {
      cleanWorkspace()
      val scala = "a/src/main/scala/a/UseScala.scala"
      val java = "a/src/main/java/a/UseJava.java"
      // The declaration line at each location.
      def definition(file: String, query: String) =
        server.definitionSubstringQuery(file, query).map { locations =>
          locations.map { l =>
            val path = l.getUri.toAbsolutePath
            val line = path.readText.linesIterator
              .drop(l.getRange.getStart.getLine)
              .next()
            s"${path.filename}: ${line.trim}"
          }
        }
      for {
        _ <- initialize(
          s"""|/metals.json
              |{ "a": {
              |  "libraryDependencies": ["org.jetbrains.kotlin:kotlin-stdlib:2.3.0"]
              |} }
              |/$scala
              |package a
              |object UseScala {
              |  val r = kotlin.random.RandomKt.Random(1)
              |}
              |/$java
              |package a;
              |class UseJava {
              |  Object xs = kotlin.collections.CollectionsKt.listOf(1);
              |  Object r = kotlin.random.RandomKt.Random(1);
              |  Object ys = kotlin.collections.CollectionsKt.listOf(1, 2);
              |}
              |""".stripMargin
        )
        _ = assertEquals(
          server.server.clientConfig.definitionIndexStrategy().value,
          strategy,
        )
        _ <- server.didOpen(scala)
        _ <- server.didOpen(java)
        scalaRandom <- definition(scala, "Rando@@m(1")
        javaListOf <- definition(java, "listO@@f(")
        javaRandom <- definition(java, "Rando@@m(1")
        javaVararg <- definition(java, "listO@@f(1, 2")
      } yield {
        val listOf =
          List(
            "CollectionsJVM.kt: public actual fun <T> listOf(element: T): List<T> = java.util.Collections.singletonList(element)"
          )
        val random =
          List(
            "Random.kt: public fun Random(seed: Int): Random = XorWowRandom(seed, seed.shr(31))"
          )
        assertEquals(scalaRandom, random)
        assertEquals(javaListOf, listOf)
        assertEquals(javaRandom, random)
        assertEquals(
          javaVararg,
          List(
            "Collections.kt: public fun <T> listOf(vararg elements: T): List<T> = if (elements.size > 0) elements.asList() else emptyList()"
          ),
        )
      }
    }
}
