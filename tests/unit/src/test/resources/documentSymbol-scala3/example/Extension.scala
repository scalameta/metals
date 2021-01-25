/*example(Package):9*/package example

/*example.extension Int(Module):4*/extension (i: Int)
  /*example.`extension Int`.asString(Method):4*/def asString: String = i.toString

/*example.extension String(Module):9*/extension (s: String) {
  /*example.`extension String`.asInt(Method):7*/def asInt: Int = s.toInt
  /*example.`extension String`.double(Method):8*/def double: String = s * 2
}

