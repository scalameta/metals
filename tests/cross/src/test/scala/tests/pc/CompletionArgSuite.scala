package tests.pc

import tests.BaseCompletionSuite
import munit.TestOptions

class CompletionArgSuite extends BaseCompletionSuite {

  check(
    TestOptions("arg").ignoreIf(isScala3),
    s"""|object Main {
        |  assert(@@)
        |}
        |""".stripMargin,
    """|assertion = : Boolean
       |Main arg
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3),
    compat = Map(
      "3.0" ->
        """|assertion = : Boolean
           |""".stripMargin
    )
  )

  check(
    "arg1",
    s"""|object Main {
        |  assert(assertion = true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |Main arg1
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3),
    compat = Map( // findPossibleDefaults and fillAllFields not yet supported
      "3.0" ->
        """|message = : => Any
           |""".stripMargin
    )
  )

  check(
    "arg2",
    s"""|object Main {
        |  assert(true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |Main arg2
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3),
    compat = Map( // findPossibleDefaults and fillAllFields not yet supported
      "3.0" ->
        """|message = : => Any
           |""".stripMargin
    )
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
       |Main arg3
       |User arg3
       |""".stripMargin,
    topLines = Option(4),
    compat = Map( // findPossibleDefaults and fillAllFields not yet supported
      "3.0" ->
        """|age = : Int
           |followers = : Int
           |""".stripMargin
    )
  )

  check(
    TestOptions("arg4").ignoreIf(isScala3),
    s"""|
        |$user
        |object Main {
        |  User("", @@, address = "")
        |}
        |""".stripMargin,
    """|age = : Int
       |followers = : Int
       |Main arg4
       |""".stripMargin,
    topLines = Option(3),
    compat = Map(
      "3.0" ->
        """|age = : Int
           |followers = : Int
           |""".stripMargin
    )
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
       |Main arg5
       |User arg5
       |""".stripMargin,
    topLines = Option(4),
    compat = Map(
      "3.0" ->
        """|age = : Int
           |followers = : Int
           |""".stripMargin
    )
  )

  check(
    TestOptions("arg6").ignoreIf(isScala3),
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
       |Main arg7
       |""".stripMargin,
    topLines = Option(2),
    compat = Map(
      "3.0" ->
        """|x = : A
           |""".stripMargin
    )
  )

  check(
    "arg8",
    s"""|
        |object Main {
        |  "".stripSuffix(@@)
        |}
        |""".stripMargin,
    """|suffix = : String
       |Main arg8
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3),
    compat = Map(
      "3.0" ->
        """|suffix = : String
           |""".stripMargin
    )
  )

  check(
    TestOptions("arg9").ignoreIf(isScala3),
    // `until` has multiple implicit conversion alternatives
    s"""|
        |object Main {
        |  1.until(@@)
        |}
        |""".stripMargin,
    """|end = : Int
       |Main arg9
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3),
    compat = Map(
      "3.0" ->
        """|end = : Int
           |""".stripMargin
    )
  )

  check(
    "arg10",
    s"""|$user
        |object Main {
        |  User(addre@@)
        |}
        |""".stripMargin,
    """|address = : String
       |""".stripMargin,
    topLines = Option(1)
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

  check(
    "arg14",
    s"""|object Main {
        |  val isLargeBanana = true
        |  processFile(isResourceFil@@)
        |  def processFile(isResourceFile: Boolean): Unit = ()
        |}
        |""".stripMargin,
    """|isResourceFile = : Boolean
       |isResourceFile = isLargeBanana : Boolean
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|isResourceFile = : Boolean
           |""".stripMargin
    )
  )

  check(
    TestOptions("priority").ignoreIf(isScala3),
    s"""|object Main {
        |  def foo(argument : Int) : Int = argument
        |  val argument = 5
        |  foo(argu@@)
        |}
        |""".stripMargin,
    """|argument: Int
       |argument = : Int
       |argument = argument : Int
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    TestOptions("named-multiple").ignoreIf(isScala3),
    s"""|object Main {
        |  def foo(argument : Int) : Int = argument
        |  val number = 1
        |  val number2 = 2
        |  val number4 = 4
        |  val number8 = 8
        |  foo(ar@@)
        |}
        |""".stripMargin,
    """|argument = : Int
       |argument = number : Int
       |argument = number2 : Int
       |argument = number4 : Int
       |argument = number8 : Int
       |""".stripMargin,
    topLines = Some(5),
    compat = Map(
      "3.0" ->
        """|argument = : Int
           |""".stripMargin
    )
  )

  checkEditLine(
    TestOptions("auto-no-show").ignoreIf(isScala3),
    s"""|object Main {
        |  def foo(argument : Int, other : String) : Int = argument
        |  val number = 5
        |  val hello = "" 
        |  val relevant = 123
        |  ___ 
        |}
        |""".stripMargin,
    "foo(rele@@)",
    "foo(relevant)"
  )

  checkEditLine(
    TestOptions("auto").ignoreIf(isScala3),
    s"""|object Main {
        |  def foo(argument : Int, other : String) : Int = argument
        |  val number = 5
        |  val hello = "" 
        |  ___ 
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1:number}, other = ${2:hello})"
  )

  checkEditLine(
    TestOptions("auto-inheritance").ignoreIf(isScala3),
    s"""|object Main {
        |  trait Animal
        |  class Dog extends Animal
        |
        |  trait Furniture
        |  class Chair extends Furniture
        |  def foo(animal: Animal, furniture: Furniture) : Int = 42
        |  val dog = new Dog()
        |  val chair = new Chair()
        |  ___ 
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(animal = ${1:dog}, furniture = ${2:chair})"
  )

  checkEditLine(
    TestOptions("auto-multiple-type").ignoreIf(isScala3),
    s"""|object Main {
        |  def foo(argument : Int, other : String, last : String = "") : Int = argument
        |  val number = 5
        |  val argument = 123
        |  val hello = "" 
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1|???,argument,number|}, other = ${2:hello})"
  )

  checkEditLine(
    TestOptions("auto-not-found").ignoreIf(isScala3),
    s"""|object Main {
        |  val number = 234
        |  val nothing = throw new Exception
        |  val nll = null
        |  def foo(argument : Int, other : String, isTrue: Boolean, opt : Option[String]) : Int = argument
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1:number}, other = ${2:???}, isTrue = ${3:???}, opt = ${4:???})"
  )

  checkEditLine(
    TestOptions("auto-list").ignoreIf(isScala3),
    s"""|object Main {
        |  def foo(argument : List[String], other : List[Int]) : Int = 0
        |  val list1 = List(1,2,3)
        |  val list2 = List(3,2,1)
        |  val list3 = List("")
        |  val list4 = List("")
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1|???,list4,list3|}, other = ${2|???,list2,list1|})"
  )

}
