package tests.pc

import tests.BaseCompletionSuite

object CompletionArgSuite extends BaseCompletionSuite {
  check(
    "arg",
    s"""|object Main {
        |  assert(@@)
        |}
        |""".stripMargin,
    """|assertion = : Boolean
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg1",
    s"""|object Main {
        |  assert(assertion = true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg2",
    s"""|object Main {
        |  assert(true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  def user: String =
    """|case class User(
       |    name: String = "John",
       |    age: Int = 42,
       |    address: String = "",
       |    followers: Int = 0
       |)
       |""".stripMargin
  check(
    "arg3",
    s"""|
        |$user
        |object Main {
        |  User("", address = "", @@)
        |}
        |""".stripMargin,
    """|age = : Int
       |followers = : Int
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg4",
    s"""|
        |$user
        |object Main {
        |  User("", @@, address = "")
        |}
        |""".stripMargin,
    """|age = : Int
       |followers = : Int
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg5",
    s"""|
        |$user
        |object Main {
        |  User("", @@ address = "")
        |}
        |""".stripMargin,
    """|address = : String
       |followers = : Int
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg6",
    s"""|
        |$user
        |object Main {
        |  User("", @@ "")
        |}
        |""".stripMargin,
    """|address = : String
       |age = : Int
       |followers = : Int
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg7",
    s"""|
        |object Main {
        |  Option[Int](@@)
        |}
        |""".stripMargin,
    """|x = : Int
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg8",
    s"""|
        |object Main {
        |  "".stripSuffix(@@)
        |}
        |""".stripMargin,
    """|suffix = : String
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg9",
    // `until` has multiple implicit conversion alternatives
    s"""|
        |object Main {
        |  1.until(@@)
        |}
        |""".stripMargin,
    """|end = : Int
       |:: scala.collection.immutable
       |:+ scala.collection
       |""".stripMargin,
    topLines = Option(3)
  )

  check(
    "arg10",
    s"""|$user
        |object Main {
        |  User(addre@@)
        |}
        |""".stripMargin,
    """|address = : String
       |""".stripMargin
  )

  check(
    "arg11",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(1)(bana@@)
        |}
        |""".stripMargin,
    """|banana = : Int
       |""".stripMargin
  )

  check(
    "arg12",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(bana@@)
        |}
        |""".stripMargin,
    ""
  )

  check(
    "arg13",
    s"""|object Main {
        |  Array("")(evidence@@)
        |}
        |""".stripMargin,
    // assert that `evidence$1` is excluded.
    ""
  )

  checkEditLine(
    "brace",
    """
      |object Main {
      |  ___
      |}
      |""".stripMargin,
    "List(1 -> 2).map { @@ }",
    "List(1 -> 2).map { case ($0) => }",
    assertSingleItem = false
  )

  check(
    "brace-label",
    """
      |object Main {
      |  List(1 -> 2).map { @@ }
      |}
      |""".stripMargin,
    """|case (Int, Int) =>: ((Int, Int)) => B
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "brace-negative",
    """
      |object Main {
      |  List(1 -> 2).map(@@)
      |}
      |""".stripMargin,
    "f = : ((Int, Int)) => B",
    topLines = Some(1)
  )

  checkEditLine(
    "brace-function2",
    """
      |object Main {
      |  ___
      |}
      |""".stripMargin,
    "List(1).foldLeft(0) { cas@@ }",
    "List(1).foldLeft(0) { case ($0) => }",
    assertSingleItem = false
  )
}
