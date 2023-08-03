package example

enum Color/*example.Color#*/(val rgb: Int):
  case Red/*example.Color.Red.*/ extends Color(0xff0000)
  case Green/*example.Color.Green.*/ extends Color(0x00ff00)
  case Blue/*example.Color.Blue.*/ extends Color(0x0000ff)
