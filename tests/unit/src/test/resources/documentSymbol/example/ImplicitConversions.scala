/*example:27*/package example

/*ImplicitConversions:27*/class ImplicitConversions {
  /*string2Number:5*/implicit def string2Number(
      string: String
  ): Int = 42
  /*message:6*/val message = ""
  /*number:7*/val number = 42
  /*tuple:8*/val tuple = (1, 2)
  /*char:9*/val char: Char = 'a'

  // extension methods
  message
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  /*x:17*/val x: Int = message

  // interpolators
  s"Hello $message $number"
  s"""Hello
     |$message
     |$number""".stripMargin

  /*a:25*/val a: Int = char
  /*b:26*/val b: Long = char
}
