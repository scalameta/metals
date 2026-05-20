package example

enum Color(val rgb: Int):
  case Red extends Color(/*rgb = */0xff0000)
  case Green extends Color(/*rgb = */0x00ff00)
  case Blue extends Color(/*rgb = */0x0000ff)