/*example*/package example

/*ImplicitConversions*/class ImplicitConversions {
  /*string2Number*/implicit def string2Number(
      string: String
  ): Int = 42
  /*message*/val message = ""
  /*number*/val number = 42
  /*tuple*/val tuple = (1, 2)
  /*char*/val char: Char = 'a'

  // extension methods
  message
    .stripSuffix("h")
  tuple + "Hello"

  // implicit conversions
  /*x*/val x: Int = message

  // interpolators
  s"Hello $message $number"
  s"""Hello
     |$message
     |$number""".stripMargin

  /*a*/val a: Int = char
  /*b*/val b: Long = char
}
