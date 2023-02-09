/*example(Package):10*/package example

/*example.ImplicitClasses(Module):10*/object ImplicitClasses {
  /*example.ImplicitClasses.Xtension(Class):6*/implicit class Xtension(number: Int) {
    /*example.ImplicitClasses.Xtension#increment(Method):5*/def increment: Int = number + 1
  }
  /*example.ImplicitClasses.XtensionAnyVal(Class):9*/implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    /*example.ImplicitClasses.XtensionAnyVal#double(Method):8*/def double: Int = number * 2
  }
}
