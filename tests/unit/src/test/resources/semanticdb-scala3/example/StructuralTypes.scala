   package example
//         ^^^^^^^ definition example/

   import reflect.Selectable.reflectiveSelectable
//        ^^^^^^^ reference scala/reflect/
//                ^^^^^^^^^^ reference scala/reflect/Selectable.
//                           ^^^^^^^^^^^^^^^^^^^^ reference scala/reflect/Selectable.reflectiveSelectable().

   object StructuralTypes:
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
//        ^^^^ reference scala/reflect/Selectable#selectDynamic().
     user.age
//   ^^^^ reference example/StructuralTypes.user.
//        ^^^ reference scala/reflect/Selectable#selectDynamic().

     val V: Object {
//       ^ definition example/StructuralTypes.V.
//          ^^^^^^ reference java/lang/Object#
       def scalameta: String
//         ^^^^^^^^^ definition local2
//                    ^^^^^^ reference scala/Predef.String#
     } = new:
       def scalameta = "4.0"
//     ^ definition local4
//         ^^^^^^^^^ definition local3
     V.scalameta
//   ^ reference example/StructuralTypes.V.
//     ^^^^^^^^^ reference scala/reflect/Selectable#selectDynamic().
   end StructuralTypes
//     ^^^^^^^^^^^^^^^ reference example/StructuralTypes.
