/*example(Package):10*/package example

// This wasn't possible in Scala 2
/*example.Xtension(Class):6*/implicit class Xtension(number: Int) {
    /*example.Xtension#increment(Method):5*/def increment: Int = number + 1
}

/*example.XtensionAnyVal(Class):10*/implicit class XtensionAnyVal(private val number: Int) extends AnyVal {
    /*example.XtensionAnyVal#double(Method):9*/def double: Int = number * 2
}
