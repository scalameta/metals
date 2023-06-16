/*example(Package):11*/package example

/*example.extension Int(Module):3*/extension (i: Int) /*example.`extension Int`.asString(Method):3*/def asString: String = i.toString

/*example.extension String(Module):7*/extension (s: String)
  /*example.`extension String`.asInt(Method):6*/def asInt: Int = s.toInt
  /*example.`extension String`.double(Method):7*/def double: String = s * 2

/*example.AbstractExtension(Interface):11*/trait AbstractExtension:
  /*example.AbstractExtension#extension Double(Module):11*/extension (d: Double)
    /*example.AbstractExtension#`extension Double`.abc(Method):11*/def abc: String
