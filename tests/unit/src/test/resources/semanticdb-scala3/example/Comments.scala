   package example
//         ^^^^^^^ definition example/

   class /* comment */ Comments {
//                     ^^^^^^^^ definition example/Comments#
     object /* comment */ A
//   ^ definition example/Comments#`<init>`().
//                        ^ definition example/Comments#A.
     trait /* comment */ A
//   ^ definition example/Comments#A#`<init>`().
//                       ^ definition example/Comments#A#
     val /* comment */ a = 1
//                     ^ definition example/Comments#a.
     def /* comment */ b = 1
//                     ^ definition example/Comments#b().
     var /* comment */ c = 1
//                     ^ definition example/Comments#c().
   }
