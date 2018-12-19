/*example(Package):28*/package example

/*example.ImplicitConversions(Class):28*/class ImplicitConversions {
  /*example.ImplicitConversions#string2Number(Method):6*/implicit def string2Number(
      string: String
  ): Int = 42
  /*example.ImplicitConversions#message(Constant):7*/val message = ""
  /*example.ImplicitConversions#number(Constant):8*/val number = 42
  /*example.ImplicitConversions#tuple(Constant):9*/val tuple = (1, 2)
  /*example.ImplicitConversions#char(Constant):10*/val char: Char = 'a'

  // extension methods
  message
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  /*example.ImplicitConversions#x(Constant):18*/val x: Int = message

  // interpolators
  s"Hello $message $number"
  s"""Hello
     |$message
     |$number""".stripMargin

  /*example.ImplicitConversions#a(Constant):26*/val a: Int = char
  /*example.ImplicitConversions#b(Constant):27*/val b: Long = char
}
