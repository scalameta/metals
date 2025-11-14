/*example(Package):6*/package example

/*example.Color(Enum):6*/enum Color(/*example.Color.rgb(Variable):3*/val rgb: Int):
  /*example.Color.Red(EnumMember):4*/case Red extends Color(0xff0000)
  /*example.Color.Green(EnumMember):5*/case Green extends Color(0x00ff00)
  /*example.Color.Blue(EnumMember):6*/case Blue extends Color(0x0000ff)
