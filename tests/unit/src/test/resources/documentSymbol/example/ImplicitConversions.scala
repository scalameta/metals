/*example(Package):28*/package example

/*ImplicitConversions(Class):28*/class ImplicitConversions {
  /*string2Number(Method):6*/implicit def string2Number(
      string: String
  ): Int = 42
  /*message(Constant):7*/val message = ""
  /*number(Constant):8*/val number = 42
  /*tuple(Constant):9*/val tuple = (1, 2)
  /*char(Constant):10*/val char: Char = 'a'

  // extension methods
  message
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  /*x(Constant):18*/val x: Int = message

  // interpolators
  s"Hello $message $number"
  s"""Hello
     |$message
     |$number""".stripMargin

  /*a(Constant):26*/val a: Int = char
  /*b(Constant):27*/val b: Long = char
}
