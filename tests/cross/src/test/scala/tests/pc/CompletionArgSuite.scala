package tests.pc

import tests.BaseCompletionSuite

class CompletionArgSuite extends BaseCompletionSuite {

  // In scala3, we get NoSymbol for `assert`, so we get no completions here.
  // This might be because the `assert` method has multiple overloaded methods, and that's why we can't retrieve a specfic symbol.
  // Might be good to fixed in Dotty.
  // see: https://github.com/scalameta/metals/pull/2369
  check(
    "arg".tag(IgnoreScala3),
    s"""|object Main {
        |  assert(@@)
        |}
        |""".stripMargin,
    """|assertion = : Boolean
       |Main arg
       |:: scala.collection.immutable
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
       |Main arg1
       |:: scala.collection.immutable
       |""".stripMargin,
    topLines = Option(3),
    compat = Map(
      "3.0" ->
        """|message = : => Any
           |Main arg1
           |ArrayCharSequence(arrayOfChars: Array[Char]): ArrayCharSequence
           |""".stripMargin
    )
  )

  checkEdit(
    "arg-edit",
    s"""|object Main {
        |  assert(assertion = true, me@@)
        |}
        |""".stripMargin,
    """|object Main {
       |  assert(assertion = true, message = )
       |}
       |""".stripMargin
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
    compat = Map(
      "3.0" ->
        """|message = : => Any
           |Main arg2
           |ArrayCharSequence(arrayOfChars: Array[Char]): ArrayCharSequence
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
    topLines = Option(4)
  )

  // We should get NamedArg `address` from args in scala3, and remove `address` from completion, but it doesn't appear.
  // This might be good to fix in Dotty.
  // see: https://github.com/scalameta/metals/pull/2369
  check(
    "arg4".tag(IgnoreScala3),
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
       |Main arg5
       |User arg5
       |""".stripMargin,
    topLines = Option(4),
    compat = Map(
      "3.0" ->
        """|age = : Int
           |followers = : Int
           |Main arg5
           |User arg5
           |""".stripMargin
    )
  )

  check(
    "arg6".tag(IgnoreScala3),
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
           |Main arg7
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
           |Main arg8
           |ArrayCharSequence(arrayOfChars: Array[Char]): ArrayCharSequence
           |""".stripMargin
    )
  )

  // In scala3, we get NoSymbol for `until`, so we get no completions here.
  // This might be because the `1.until` method has multiple overloaded methods, like `until(end: Long)` and `until(start: Long, end: Long)`,
  // and that's why we can't retrieve a specfic symbol.
  // Might be good to fixed in Dotty.
  // see: https://github.com/scalameta/metals/pull/2369
  check(
    "arg9".tag(IgnoreScala3),
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
    topLines = Some(3),
    compat = Map(
      "3.0" ->
        """|argument = : Int
           |argument: Int
           |""".stripMargin
    )
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
    topLines = Some(5),
    compat = Map(
      "3.0.0-RC1" ->
        """|argument = : Int
           |""".stripMargin,
      "3.0" ->
        """|argument = : Int
           |Calendar - java.util
           |""".stripMargin
    )
  )

  checkEditLine(
    "auto-no-show".tag(IgnoreScala3),
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
    "auto".tag(IgnoreScala3),
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
    "auto-inheritance".tag(IgnoreScala3),
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
    "auto-multiple-type".tag(IgnoreScala3),
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
    "auto-not-found".tag(IgnoreScala3),
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
    "auto-list".tag(IgnoreScala3),
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
