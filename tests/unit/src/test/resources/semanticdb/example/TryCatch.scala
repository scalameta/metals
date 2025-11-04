   package example
//         ^^^^^^^ definition example/

   class TryCatch {
//       ^^^^^^^^ definition example/TryCatch#
//                ^ definition example/TryCatch#`<init>`().
     try {
       val x = 2
//         ^ definition local0
       x + 2
//     ^ reference local0
//       ^ reference scala/Int#`+`(+4).
     } catch {
       case t: Throwable =>
//          ^ definition local1
//             ^^^^^^^^^ reference scala/package.Throwable#
         t.printStackTrace()
//       ^ reference local1
//         ^^^^^^^^^^^^^^^ reference java/lang/Throwable#printStackTrace().
     } finally {
       val text = ""
//         ^^^^ definition local2
       text + ""
//     ^^^^ reference local2
//          ^ reference java/lang/String#`+`().
     }
   }
