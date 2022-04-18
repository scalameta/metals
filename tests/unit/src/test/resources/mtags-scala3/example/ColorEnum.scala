package example

enum Color/*example.Color#*/(val rgb/*example.Color#rgb.*/: Int):
   case Red/*example.Color.Red.*/   extends Color(0xFF0000)
   case Green/*example.Color.Green.*/ extends Color(0x00FF00)
   case Blue/*example.Color.Blue.*/  extends Color(0x0000FF)
