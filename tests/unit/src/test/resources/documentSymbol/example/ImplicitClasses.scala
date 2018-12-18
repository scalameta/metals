/*example*/package example

/*ImplicitClasses*/object ImplicitClasses {
  /*Xtension*/implicit class Xtension(number: Int) {
    /*increment*/def increment: Int = number + 1
  }
  /*XtensionAnyVal*/implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    /*double*/def double: Int = number * 2
  }
}
