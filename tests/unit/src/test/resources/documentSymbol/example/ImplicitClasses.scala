/*example:10*/package example

/*ImplicitClasses:10*/object ImplicitClasses {
  /*Xtension:6*/implicit class Xtension(number: Int) {
    /*increment:5*/def increment: Int = number + 1
  }
  /*XtensionAnyVal:9*/implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    /*double:8*/def double: Int = number * 2
  }
}
