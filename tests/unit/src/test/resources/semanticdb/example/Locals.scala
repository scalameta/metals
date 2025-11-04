   package example
//         ^^^^^^^ definition example/

   class Locals {
//       ^^^^^^ definition example/Locals#
//              ^ definition example/Locals#`<init>`().
     {
       val x = 2
//         ^ definition local0
       x + 2
//     ^ reference local0
//       ^ reference scala/Int#`+`(+4).
     }
   }
