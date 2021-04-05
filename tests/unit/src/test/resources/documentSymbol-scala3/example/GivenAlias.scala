/*example(Package):13*/package example

/*example.intValue(Constant):3*/given intValue: Int = 4
/*example. (Constant):4*/given String = "str"

/*example.method(Method):6*/def method(using Int) = ""

/*example.anonUsage(Constant):8*/val anonUsage = given_String

/*example.X(Module):13*/object X {
  /*example.X. (Constant):11*/given Double = 4.0
  /*example.X.double(Constant):12*/val double = given_Double
}
