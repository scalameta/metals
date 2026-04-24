   package example
//         ^^^^^^^ definition example/

   enum Color(val rgb: Int):
//      ^^^^^ definition example/Color#
//           ^ definition example/Color#`<init>`().
//                ^^^ definition example/Color#rgb.
//                     ^^^ reference scala/Int#
     case Red extends Color(0xff0000)
//        ^^^ definition example/Color.Red.
//                    ^^^^^ reference example/Color#
     case Green extends Color(0x00ff00)
//        ^^^^^ definition example/Color.Green.
//                      ^^^^^ reference example/Color#
     case Blue extends Color(0x0000ff)
//        ^^^^ definition example/Color.Blue.
//                     ^^^^^ reference example/Color#
