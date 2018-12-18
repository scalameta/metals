/*example(Package):10*/package example

/*ImplicitClasses(Module):10*/object ImplicitClasses {
  /*Xtension(Class):6*/implicit class Xtension(number: Int) {
    /*increment(Method):5*/def increment: Int = number + 1
  }
  /*XtensionAnyVal(Class):9*/implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    /*double(Method):8*/def double: Int = number * 2
  }
}
