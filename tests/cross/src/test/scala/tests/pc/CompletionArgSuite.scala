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
       |assertion = false : Boolean
       |Main arg
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(4)
  )

  check(
    "arg1",
    s"""|object Main {
        |  assert(assertion = true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |message = ??? : => Any
       |Main arg1
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(4)
  )

  check(
    "arg2",
    s"""|object Main {
        |  assert(true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |message = ??? : => Any
       |Main arg2
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(4)
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
       |age = 0 : Int
       |followers = 0 : Int
       |Main arg3
       |User arg3
       |""".stripMargin,
    topLines = Option(6)
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
       |age = 0 : Int
       |followers = 0 : Int
       |Main arg4
       |""".stripMargin,
    topLines = Option(5)
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
       |address = "" : String
       |followers = 0 : Int
       |Main arg5
       |User arg5
       |""".stripMargin,
    topLines = Option(6)
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
       |x = 0 : Int
       |Main arg7
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
       |suffix = "" : String
       |Main arg8
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(4)
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
       |end = 0 : Int
       |Main arg9
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(4)
  )

  check(
    "arg10",
    s"""|$user
        |object Main {
        |  User(addre@@)
        |}
        |""".stripMargin,
    """|address = : String
       |address = "" : String
       |""".stripMargin,
    topLines = Option(2)
  )

  check(
    "arg11",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(1)(bana@@)
        |}
        |""".stripMargin,
    """|banana = : Int
       |banana = 0 : Int
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
    "priority",
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
    "named-multiple",
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
    topLines = Some(5)
  )

  checkEditLine(
    "auto",
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
    "auto-multiple-type",
    s"""|object Main {
        |  def foo(argument : Int, other : String) : Int = argument
        |  val number = 5
        |  val argument = 123
        |  val hello = "" 
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1|argument,number|}, other = ${2:hello})"
  )

  checkEditLine(
    "auto-default",
    s"""|object Main {
        |  def foo(argument : Int, other : String, isTrue: Boolean, opt : Option[String]) : Int = argument
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1:0}, other = ${2:\"\"}, isTrue = ${3:false}, opt = ${4:None})"
  )

  checkEditLine(
    "auto-list",
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
    "foo(argument = ${1|list4,list3|}, other = ${2|list2,list1|})"
  )

}
