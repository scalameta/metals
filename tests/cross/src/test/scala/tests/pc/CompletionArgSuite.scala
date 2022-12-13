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
       |""".stripMargin,
    topLines = Option(2),
  )

  check(
    "arg-newline",
    s"""|object Main {
        |  def foo(banana: String, apple: String) = ???
        |  foo(
        |    @@
        |  )
        |}
        |""".stripMargin,
    """|apple = : String
       |banana = : String
       |""".stripMargin,
    topLines = Option(2),
  )

  check(
    "arg1",
    s"""|object Main {
        |  assert(assertion = true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |Main arg1
       |""".stripMargin,
    topLines = Option(2),
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
       |""".stripMargin,
  )

  check(
    "arg2",
    s"""|object Main {
        |  assert(true, @@)
        |}
        |""".stripMargin,
    """|message = : => Any
       |Main arg2
       |""".stripMargin,
    topLines = Option(2),
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
  )

  // We should get NamedArg `address` from args in scala3, and remove `address` from completion, but it doesn't appear.
  // This might be good to fix in Dotty.
  // see: https://github.com/scalameta/metals/pull/2369
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
       |Main arg4
       |""".stripMargin,
    topLines = Option(3),
    compat = Map(
      "3.1.3" ->
        """|age = : Int
           |followers = : Int
           |Main arg4
           |""".stripMargin,
      "3.1" ->
        """|address = : String
           |age = : Int
           |followers = : Int
           |""".stripMargin,
    ),
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
      "3" ->
        """|age = : Int
           |followers = : Int
           |Main arg5
           |User arg5
           |""".stripMargin
    ),
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
    topLines = Option(3),
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
      "3" ->
        """|x = : A
           |Main arg7
           |""".stripMargin
    ),
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
       |""".stripMargin,
    topLines = Option(2),
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
       |""".stripMargin,
    topLines = Option(2),
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
    topLines = Option(1),
  )

  check(
    "arg11",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(1)(bana@@)
        |}
        |""".stripMargin,
    """|banana = : Int
       |""".stripMargin,
  )

  check(
    "arg12",
    s"""|object Main {
        |  def curry(a: Int)(banana: Int): Int = ???
        |  curry(bana@@)
        |}
        |""".stripMargin,
    "",
  )

  check(
    "arg13",
    s"""|object Main {
        |  Array("")(evidence@@)
        |}
        |""".stripMargin,
    // assert that `evidence$1` is excluded.
    "",
  )

  checkSnippet( // see: https://github.com/scalameta/metals/issues/2400
    "explicit-dollar",
    """
      |object Main {
      |  def test($foo: Int, $bar: Int): Int = ???
      |  test($f@@)
      |}
      |""".stripMargin,
    """|$$foo = 
       |""".stripMargin,
    topLines = Option(1),
  )

  // known issue: the second parameter with $ become | (returned from compiler)
  // see: https://github.com/scalameta/metals/issues/3690
  checkSnippet(
    "explicit-dollar-autofill",
    """
      |object Main {
      |  def test($foo: Int, $bar: Int): Int = ???
      |  test($f@@)
      |}
      |""".stripMargin,
    """|$$foo = 
       |$$foo = ${1:???}, | = ${2:???}
       |""".stripMargin,
    topLines = Option(2),
    compat = Map(
      "3" -> """|$$foo = 
                |$$foo = ${1:???}, $$bar = ${2:???}
                |""".stripMargin
    ),
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
  )

  check(
    "priority-2",
    s"""|case class A(argument: Int)
        |object Main {
        |  def foo(argument: Int): A =
        |    A(argu@@)
        |}
        |""".stripMargin,
    """|argument: Int
       |argument = : Int
       |argument = argument : Int
       |""".stripMargin,
    topLines = Some(3),
  )

  check(
    "named-multiple",
    s"""|object Main {
        |  def foo(argument : Int) : Int = argument
        |  val number = 1
        |  val number2 = 2
        |  val number4 = 4
        |  val number8 = 8
        |  foo(argu@@)
        |}
        |""".stripMargin,
    """|argument = : Int
       |argument = number : Int
       |argument = number2 : Int
       |argument = number4 : Int
       |argument = number8 : Int
       |""".stripMargin,
    topLines = Some(5),
  )

  check(
    "named-backticked",
    s"""|object Main {
        |  def foo(`type` : Int) : Int = argument
        |  val number = 1
        |  val number2 = 2
        |  foo(ty@@)
        |}
        |""".stripMargin,
    """|`type` = : Int
       |`type` = number : Int
       |`type` = number2 : Int
       |""".stripMargin,
    topLines = Some(5),
  )

  checkEditLine(
    "auto-no-show",
    s"""|object Main {
        |  def foo(argument : Int, other : String) : Int = argument
        |  val number = 5
        |  val hello = "" 
        |  val relevant = 123
        |  ___ 
        |}
        |""".stripMargin,
    "foo(rele@@)",
    "foo(relevant)",
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
    "foo(argument = ${1:number}, other = ${2:hello})",
  )

  checkEditLine(
    "auto-inheritance",
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
    "foo(animal = ${1:dog}, furniture = ${2:chair})",
  )

  checkEditLine(
    "auto-multiple-type",
    s"""|object Main {
        |  def foo(argument : Int, other : String, last : String = "") : Int = argument
        |  val number = 5
        |  val argument = 123
        |  val hello = "" 
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1|???,argument,number|}, other = ${2:hello})",
  )

  checkEditLine(
    "auto-not-found",
    s"""|object Main {
        |  val number = 234
        |  val nothing = throw new Exception
        |  val nll = null
        |  def foo(argument : Int, other : String, isTrue: Boolean, opt : Option[String]) : Int = argument
        |  ___
        |}
        |""".stripMargin,
    "foo(auto@@)",
    "foo(argument = ${1:number}, other = ${2:???}, isTrue = ${3:???}, opt = ${4:???})",
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
    "foo(argument = ${1|???,list4,list3|}, other = ${2|???,list2,list1|})",
    compat = Map(
      "3" -> "foo(argument = ${1|???,list3,list4|}, other = ${2|???,list1,list2|})"
    ),
  )

  checkEditLine(
    "wrap-idents",
    s"""|object Main {
        |  def f(a: String, b: String, `type`: String) = a + b + `type`
        |  val str = ""
        |  val str1 = ""
        |  ___
        |}
        |""".stripMargin,
    "f(auto@@)",
    "f(a = ${1|???,str1,str|}, b = ${2|???,str1,str|}, `type` = ${3|???,str1,str|})",
    compat = Map(
      "3" -> "f(a = ${1|???,str,str1|}, b = ${2|???,str,str1|}, `type` = ${3|???,str,str1|})"
    ),
  )

  check(
    "nested-apply",
    s"""|object Main{
        |  def foo(argument1: Int, argument2: Int): Int = argument1 + argument2
        |  val x: Int = 3
        |  foo(foo(@@), )
        |}
        |""".stripMargin,
    """|argument1 = : Int
       |argument2 = : Int
       |argument1 = x : Int
       |argument2 = x : Int
       |""".stripMargin,
    topLines = Some(4),
    compat = Map(
      /* Minor implementation detail between Scala 2 and Scala 3
       * which shouldn't cause any issue and making it work the same
       * would require non trivial code. `argument1 = x ` is not a
       * NamedArgument in Scala 2 but a simple TextEditMember, which means
       * `argument1 = ` will be prioritized.
       */
      "3" ->
        """|argument1 = : Int
           |argument1 = x : Int
           |argument2 = : Int
           |argument2 = x : Int
           |""".stripMargin
    ),
  )

}
