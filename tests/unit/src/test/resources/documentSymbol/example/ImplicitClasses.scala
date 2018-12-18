/*example:9*/package example

/*ImplicitClasses:9*/object ImplicitClasses {
  /*Xtension:5*/implicit class Xtension(number: Int) {
    /*increment:4*/def increment: Int = number + 1
  }
  /*XtensionAnyVal:8*/implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    /*double:7*/def double: Int = number * 2
  }
}
