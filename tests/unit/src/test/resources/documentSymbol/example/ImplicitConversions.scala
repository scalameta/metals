/*example:28*/package example

/*ImplicitConversions:28*/class ImplicitConversions {
  /*string2Number:6*/implicit def string2Number(
      string: String
  ): Int = 42
  /*message:7*/val message = ""
  /*number:8*/val number = 42
  /*tuple:9*/val tuple = (1, 2)
  /*char:10*/val char: Char = 'a'

  // extension methods
  message
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  /*x:18*/val x: Int = message

  // interpolators
  s"Hello $message $number"
  s"""Hello
     |$message
     |$number""".stripMargin

  /*a:26*/val a: Int = char
  /*b:27*/val b: Long = char
}
