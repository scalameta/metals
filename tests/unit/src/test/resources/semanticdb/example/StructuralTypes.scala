   package example
//         ^^^^^^^ definition example/

   object StructuralTypes {
//        ^^^^^^^^^^^^^^^ definition example/StructuralTypes.
     type User = {
//        ^^^^ definition example/StructuralTypes.User#
       def name: String
//         ^^^^ definition local0
//               ^^^^^^ reference scala/Predef.String#
       def age: Int
//         ^^^ definition local1
//              ^^^ reference scala/Int#
     }

     val user = null.asInstanceOf[User]
//       ^^^^ definition example/StructuralTypes.user.
//                   ^^^^^^^^^^^^ reference scala/Any#asInstanceOf().
//                                ^^^^ reference example/StructuralTypes.User#
     user.name
//   ^^^^ reference example/StructuralTypes.user.
//        ^^^^ reference local0
     user.age
//   ^^^^ reference example/StructuralTypes.user.
//        ^^^ reference local1

     val V: Object {
//       ^ definition example/StructuralTypes.V.
//          ^^^^^^ reference java/lang/Object#
       def scalameta: String
//         ^^^^^^^^^ definition local2
//                    ^^^^^^ reference scala/Predef.String#
     } = new {
//           ^ definition local3
       def scalameta = "4.0"
//         ^^^^^^^^^ definition local4
     }
     V.scalameta
//   ^ reference example/StructuralTypes.V.
//     ^^^^^^^^^ reference local2
   }
