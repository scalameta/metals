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
    topLines = Option(2)
  )

  check(
    "arg-newline",
    s"""|object Main {
        |  def foo(banana: String, tomato: String) = ???
        |  foo(
        |    @@
        |  )
        |}
        |""".stripMargin,
    """|banana = : String
       |tomato = : String
       |""".stripMargin,
    topLines = Option(2)
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
    topLines = Option(2)
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
       |""".stripMargin,
    topLines = Option(2)
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
           |""".stripMargin
    )
  )

  check(
    "arg5",
    s"""|
        |$user
        |object Main {
        |  User("", @@, address = "")
        |}
        |""".stripMargin,
    """|age = : Int
       |followers = : Int
       |Main arg5
       |User arg5
       |""".stripMargin,
    topLines = Option(4)
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
    """|x = : A
       |Main arg7
       |""".stripMargin,
    topLines = Option(2)
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
    topLines = Option(2)
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
    topLines = Option(2)
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
    "default-args".tag(IgnoreScala211),
    """|object Main {
       |  def foo() = {
       |    def deployment(
       |      fst: String,
       |      snd: Int = 1,
       |    ): Option[Int] = ???
       |    val abc = deployment(@@)
       |  }
       |}
       |""".stripMargin,
    """|fst = : String
       |snd = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "default-args2".tag(IgnoreScala211),
    """|object Main {
       |  def deployment(
       |    fst: String,
       |    snd: Int = 1,
       |  ): Option[Int] = ???
       |  val abc = deployment(@@)
       |}
       |""".stripMargin,
    """|fst = : String
       |snd = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "default-args3".tag(IgnoreScala211),
    """|object Main {
       |  def deployment(str: String)(
       |    fst: String,
       |    snd: Int = 1,
       |  ): Option[Int] = ???
       |  val abc = deployment("str")(
       |    @@
       |  )
       |}
       |""".stripMargin,
    """|fst = : String
       |snd = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "default-args4".tag(IgnoreScala211),
    """|object Main {
       |  def deployment(str: String)(opt: Option[Int])(
       |    fst: String,
       |    snd: Int = 1,
       |  ): Option[Int] = ???
       |  val abc = deployment("str")(None)(
       |    @@
       |  )
       |}
       |""".stripMargin,
    """|fst = : String
       |snd = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  // NOTE: In Scala 3.3.1 the tree for this test changed, which allowed easy fix
  // For previous Scala 3 versions it shows wrong completions
  check(
    "default-args5".tag(IgnoreScala211),
    """|object Main {
       |  def deployment(str: String)(opt: Option[Int] = None)(
       |    fst: String,
       |    snd: Int = 1,
       |  ): Option[Int] = ???
       |  val abc = deployment("str")(
       |    @@
       |  )
       |}
       |""".stripMargin,
    """|abc: (String, Int) => Option[Int]
       |""".stripMargin,
    topLines = Some(1),
    compat = Map(
      ">=3.3.1" ->
        """|opt = : Option[Int]
           |""".stripMargin,
      "2" ->
        """|opt = : Option[Int]
           |""".stripMargin
    )
  )

  check(
    "default-args6".tag(IgnoreScala2),
    """|object Main {
       |  def deployment(using str: String)(
       |    fst: String,
       |    snd: Int = 1,
       |  ): Option[Int] = ???
       |  val abc = deployment(using "str")(
       |    @@
       |  )
       |}
       |""".stripMargin,
    """|fst = : String
       |snd = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  checkSnippet( // see: https://github.com/scalameta/metals/issues/2400
    "explicit-dollar",
    """
      |object Main {
      |  def test($foo: Int, $bar: Int): Int = ???
      |  test($f@@)
      |}
      |""".stripMargin,
    """|$$foo = """.stripMargin,
    topLines = Option(1)
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
    )
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
       |""".stripMargin
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
        |  foo(argu@@)
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
    topLines = Some(5)
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
    "foo(relevant)"
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
    "foo(animal = ${1:dog}, furniture = ${2:chair})"
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
    "foo(argument = ${1|???,argument,number|}, other = ${2:hello})"
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
    "foo(argument = ${1:number}, other = ${2:???}, isTrue = ${3:???}, opt = ${4:???})"
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
    )
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
    )
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
    )
  )

  check(
    "infix",
    s"""|object Main{
        |  val lst: List[Int] = List(1, 2, 3)
        |  lst.map(x => x * x@@ )
        |}
        |""".stripMargin,
    """|x: Int
       |""".stripMargin
  )

  check(
    "contructor-param",
    """|class Foo (xxx: Int)
       |
       |object Main {
       |  val foo = new Foo(x@@)
       |}
       |""".stripMargin,
    """|xxx = : Int
       |""".stripMargin
  )

  check(
    "contructor-param2",
    """|class Foo ()
       |
       |object Foo {
       |  def apply(xxx: Int): Foo = ???
       |}
       |object Main {
       |  val foo = Foo(x@@)
       |}
       |""".stripMargin,
    """|xxx = : Int
       |""".stripMargin
  )

  check(
    "context-function-as-param".tag(IgnoreScala2),
    s"""|case class Context()
        |
        |def foo(arg1: (Context) ?=> Int, arg2: Int): String = ???
        |val m = foo(ar@@)
        |""".stripMargin,
    """|arg1 = : (Context) ?=> Int
       |arg2 = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "context-function-as-param2".tag(IgnoreScala2),
    s"""|case class Context()
        |
        |def foo(arg1: Context ?=> Int, arg2: Context ?=> Int): String = ???
        |val m = foo(arg1 = ???, a@@)
        |""".stripMargin,
    """|arg2 = : (Context) ?=> Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "context-function-as-param3".tag(IgnoreScala2),
    s"""|case class Context()
        |
        |def foo(arg1: (Boolean, Context) ?=> Int ?=> String, arg2: (Boolean, Context) ?=> Int ?=> String): String = ???
        |val m = foo(arg1 = ???, a@@)
        |""".stripMargin,
    """|arg2 = : (Boolean, Context) ?=> (Int) ?=> String
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "second-arg-first".tag(IgnoreScala211),
    """|case class Test(
       |    testA: String,
       |    testB: Option[String],
       |    testC: String,
       |)
       |object Main {
       |  def test(x: Test) = {
       |    x.copy(testB = ???, te@@)
       |  }
       |}
       |""".stripMargin,
    """|testA = : String
       |testC = : String
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "case-class-apply",
    """|object Main {
       |  def m() = {
       |    case class A(foo: Int, fooBar: Int)
       |    println(A(foo@@))
       |  }
       |}
       |""".stripMargin,
    """|foo = : Int
       |fooBar = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "case-class-apply1".tag(IgnoreScala211),
    """|object Main {
       |  def m() = {
       |    case class A(foo: Int, fooBar: Int)
       |    object A { def apply(foo: Int) = new A(foo, 3) }
       |    println(A(foo@@))
       |  }
       |}
       |""".stripMargin,
    """|foo = : Int
       |fooBar = : Int
       |""".stripMargin
  )

  check(
    "case-class-apply2".tag(IgnoreScala211),
    """|object Main {
       |  def m() = {
       |    case class A(foo: Int, fooBar: Int)
       |    object A { def apply(foo: Int) = new A(foo, 3) }
       |    println(A(foo = 1, foo@@))
       |  }
       |}
       |""".stripMargin,
    """|fooBar = : Int
       |""".stripMargin
  )

  check(
    "case-class-apply3",
    """|case class A(val foo: Int, val fooBar: Int)
       |object A {
       |  def apply(foo: Int): A = new A(foo, 3)
       |  def m = 3
       |}
       |
       |object Main {
       |  for {
       |      a <- List(1, 2, 3)
       |      x = A(foo@@)
       |   }
       |}
       |""".stripMargin,
    """|foo = : Int
       |fooBar = : Int
       |foo = a : Int
       |fooBar = a : Int
       |""".stripMargin,
    topLines = Some(4),
    compat = Map(
      "3" ->
        """|foo = : Int
           |foo = a : Int
           |fooBar = : Int
           |fooBar = a : Int
           |""".stripMargin
    )
  )

  check(
    "case-class-apply4",
    """|case class A(val foo: Int, val fooBar: Int)
       |object A {
       |  def apply(foo: Int): A = new A(foo, 3)
       |}
       |
       |object Main {
       |  for {
       |      a <- List(1, 2, 3)
       |      x = A(foo = 1, foo@@)
       |   }
       |}
       |""".stripMargin,
    """|fooBar = : Int
       |fooBar = a : Int
       |""".stripMargin
  )

  check(
    "case-class-for-comp",
    """|case class Abc(foo: Int, fooBar: Int)
       |object Main {
       |   for {
       |      a <- List(1, 2, 3)
       |      x = Abc(foo@@)
       |   }
       |}
       |""".stripMargin,
    """|foo = : Int
       |fooBar = : Int
       |foo = a : Int
       |fooBar = a : Int
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|foo = : Int
           |foo = a : Int
           |fooBar = : Int
           |fooBar = a : Int
           |""".stripMargin
    ),
    topLines = Some(4)
  )

  check(
    "recursive",
    """|
       |object Main {
       |   def foo(value: Int): Int = {
       |     foo(valu@@)
       |   }
       |}
       |""".stripMargin,
    """|value: Int
       |value = : Int
       |value = value : Int
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|value = : Int
           |value = value : Int
           |value: Int
           |""".stripMargin
    ),
    topLines = Some(4)
  )
  check(
    "overloaded",
    """|object Main {
       |  def m(inn : Int) = ???
       |  def m(idd : Option[Int]) = ???
       |  def k = m(i@@)
       |}
       |""".stripMargin,
    """|idd = : Option[Int]
       |inn = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "overloaded-with-param",
    """|object Main {
       |  def m(idd : String, abb: Int): Int = ???
       |  def m(inn : Int, uuu: Option[Int]): Int = ???
       |  def m(inn : Int, aaa: Int): Int = ???
       |  def k: Int = m(1, a@@)
       |}
       |""".stripMargin,
    """|aaa = : Int
       |assert(assertion: Boolean): Unit
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "overloaded-with-named-param",
    """|object Main {
       |  def m(idd : String, abb: Int): Int = ???
       |  def m(inn : Int, uuu: Option[Int]): Int = ???
       |  def m(inn : Int, aaa: Int): Int = ???
       |  def k: Int = m(inn = 1, a@@)
       |}
       |""".stripMargin,
    """|aaa = : Int
       |assert(assertion: Boolean): Unit
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "overloaded-generic",
    """|object Main {
       |  val h = 3
       |  val l : List[Int] = List(1,2,3)
       |  def m[T](inn : List[T], yy: Int, aaa: Int, abb: Option[Int]): Int = ???
       |  def m[T](inn : List[T], yy: Int, aaa: Int, abb: Int): Int = ???
       |  def k: Int = m(yy = 3, inn = l, a@@)
       |}
       |""".stripMargin,
    """|aaa = : Int
       |abb = : Int
       |abb = : Option[Int]
       |aaa = h : Int
       |abb = h : Int
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|aaa = : Int
           |aaa = h : Int
           |abb = : Option[Int]
           |abb = : Int
           |abb = h : Int
           |""".stripMargin
    ),
    topLines = Some(5)
  )

  check(
    "overloaded-methods",
    """|class A() {
       |  def m(anInt : Int): Int = ???
       |  def m(aString : String): String = ???
       |}
       |object O {
       |  def m(aaa: Int): Int = ???
       |  val k = new A().m(a@@)
       |}
       |""".stripMargin,
    """|aString = : String
       |anInt = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  // type of `myInstance` is resolved to `null` for Scala 2
  check(
    "overloaded-methods2".tag(IgnoreScala2),
    """|class A() {
       |  def m(anInt : Int): Int = ???
       |  def m(aString : String): String = ???
       |  private def m(aBoolean: Boolean): Boolean = ???
       |}
       |object O {
       |  def m(aaa: Int): Int = ???
       |  val myInstance = new A()
       |  val k = myInstance.m(a@@)
       |}
       |""".stripMargin,
    """|aString = : String
       |anInt = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "overloaded-select",
    """|package a.b {
       |  object A {
       |    def m(anInt : Int): Int = ???
       |    def m(aString : String): String = ???
       |  }
       |}
       |object O {
       |  def m(aaa: Int): Int = ???
       |  val k = a.b.A.m(a@@)
       |}
       |""".stripMargin,
    """|aString = : String
       |anInt = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "overloaded-in-a-class",
    """|trait Planet
       |case class Venus() extends Planet
       |class Main[T <: Planet](t : T) {
       |  def m(inn: Planet, abb: Option[Int]): Int = ???
       |  def m(inn: Planet, aaa: Int): Int = ???
       |  def k = m(t, a@@)
       |}
       |""".stripMargin,
    """|aaa = : Int
       |abb = : Option[Int]
       |""".stripMargin,
    topLines = Some(2)
  )

  // In Scala 2 first argument list needs to disambiguate between overloaded methods.
  check(
    "overloaded-function-param".tag(IgnoreScala2),
    """|def m[T](i: Int)(inn: T => Int, abb: Option[Int]): Int = ???
       |def m[T](i: Int)(inn: T => Int, aaa: Int): Int = ???
       |def m[T](i: Int)(inn: T => String, acc: List[Int]): Int = ???
       |def k = m(1)(inn = identity[Int], a@@)
       |""".stripMargin,
    """|aaa = : Int
       |abb = : Option[Int]
       |assert(assertion: Boolean): Unit
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "overloaded-function-param2".tag(IgnoreScala2),
    """|def m[T](i: Int)(inn: T => Int, abb: Option[Int]): Int = ???
       |def m[T](i: Int)(inn: T => Int, aaa: Int): Int = ???
       |def m[T](i: String)(inn: T => Int, acc: List[Int]): Int = ???
       |def k = m(1)(inn = identity[Int], a@@)
       |""".stripMargin,
    """|aaa = : Int
       |abb = : Option[Int]
       |assert(assertion: Boolean): Unit
       |""".stripMargin,
    topLines = Some(3)
  )

  // doesn't filter properly for Scala 2, since the filtering heuristic for matching methods
  // isn't accurate enough (polymorphic args types are resolved to `null`)
  // issue: https://github.com/scalameta/metals/issues/5406
  check(
    "overloaded-applied-type".tag(IgnoreScala2),
    """|trait MyCollection[+T]
       |case class IntCollection() extends MyCollection[Int]
       |object Main {
       |  def m[T](inn: MyCollection[T], abb: Option[Int]): Int = ???
       |  def m[T](inn: MyCollection[T], aaa: Int): Int = ???
       |  def m[T](inn: List[T], acc: Int): Int = ???
       |  def k = m(IntCollection(), a@@)
       |}
       |""".stripMargin,
    """|aaa = : Int
       |abb = : Option[Int]
       |assert(assertion: Boolean): Unit
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "overloaded-bounds".tag(IgnoreScala2),
    """|trait Planet
       |case class Moon()
       |object Main {
       |  def m[M](inn: M, abb: Option[Int]): M = ???
       |  def m[M](inn: M, acc: List[Int]): M = ???
       |  def m[M <: Planet](inn: M, aaa: Int): M = ???
       |  def k = m(Moon(), a@@)
       |}
       |""".stripMargin,
    """|abb = : Option[Int]
       |acc = : List[Int]
       |assert(assertion: Boolean): Unit
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "overloaded-or-type".tag(IgnoreScala2),
    """|object Main:
       |  val h : Int = 3
       |  def m[T](inn: String | T, abb: Option[Int]): Int = ???
       |  def m(inn: Int, aaa: Int): Int = ???
       |  def k: Int = m(3, a@@)
       |""".stripMargin,
    """|aaa = : Int
       |aaa = h : Int
       |abb = : Option[Int]
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "overloaded-function-param3".tag(IgnoreScala2),
    """|def m[T](inn: Int => T, abb: Option[Int]): Int = ???
       |def m[T](inn: String => T, aaa: Int): Int = ???
       |def k = m(identity[Int], a@@)
       |""".stripMargin,
    """|abb = : Option[Int]
       |""".stripMargin,
    topLines = Some(1)
  )

  // issue: https://github.com/scalameta/metals/issues/5407
  check(
    "overloaded-extension-methods".ignore,
    """|extension(i: Int)
       |  def m(inn : Int, aaa : Int): Int = ???
       |  def m(inn : Int, abb : Option[Int]): Int = ???
       |
       |val k = 1.m(3, a@@)
       |""".stripMargin,
    """|aaa = : Int
       |abb = : Option[Int]
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "lambda".tag(IgnoreScala2),
    """|val hello: (x: Int) => Unit = x => println(x)
       |val k = hello(@@)
       |""".stripMargin,
    """|x = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "lambda2".tag(IgnoreScala2),
    """|object O:
       |  val hello: (x: Int, y: Int) => Unit = (x, _) => println(x)
       |val k = O.hello(x = 1, @@)
       |""".stripMargin,
    """|y = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "lambda3".tag(IgnoreScala2),
    """|val hello: (x: Int) => (j: Int) => Unit = x => j => println(x)
       |val k = hello(@@)
       |""".stripMargin,
    """|x = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "lambda4".tag(IgnoreScala2),
    """|val hello: (x: Int) => (j: Int) => (str: String) => Unit = x => j => str => println(str)
       |val k = hello(x = 1)(2)(@@)
       |""".stripMargin,
    """|str = : String
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "lambda5".tag(IgnoreScala2),
    """|val hello: (x: Int) => Int => (str: String) => Unit = x => j => str => println(str)
       |val k = hello(x = 1)(2)(@@)
       |""".stripMargin,
    """|str = : String
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "second-first",
    """|object Main {
       |  def foo(aaa: Int, bbb: Int, ccc: Int) = aaa + bbb + ccc
       |  val k = foo (
       |    bbb = 123,
       |    aa@@
       |  )
       |} 
       |""".stripMargin,
    """|aaa = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "second-first2",
    """|object Main {
       |  def foo(aaa: Int, bbb: Int, ccc: Int) = aaa + bbb + ccc
       |  val k = foo (
       |    bbb = 123,
       |    ccc = 123,
       |    aa@@
       |  )
       |} 
       |""".stripMargin,
    """|aaa = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "second-first3",
    """|object Main {
       |  def foo(ddd: Int)(aaa: Int, bbb: Int, ccc: Int) = aaa + bbb + ccc
       |  val k = foo(123)(
       |    bbb = 123,
       |    ccc = 123,
       |    aa@@
       |  )
       |} 
       |""".stripMargin,
    """|aaa = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "second-first4".tag(IgnoreScala2),
    """|object O:
       |  val hello: (x: Int, y: Int) => Unit = (x, _) => println(x)
       |val k = O.hello(y = 1, @@)
       |""".stripMargin,
    """|x = : Int
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "second-first5".tag(IgnoreScala2),
    """|val hello: (x: Int) => Int => (str: String, ccc: String) => Unit = x => j => (str, _) => println(str)
       |val k = hello(x = 1)(2)(ccc = "abc", @@)
       |""".stripMargin,
    """|str = : String
       |""".stripMargin,
    topLines = Some(1)
  )

}
