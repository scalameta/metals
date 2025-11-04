   package example
//         ^^^^^^^ definition example/

   class ReflectiveInvocation {
//       ^^^^^^^^^^^^^^^^^^^^ definition example/ReflectiveInvocation#
//                            ^ definition example/ReflectiveInvocation#`<init>`().
     new Serializable {
//       ^ definition local1
//       ^^^^^^^^^^^^ reference scala/package.Serializable#
//                    ^ reference java/lang/Object#`<init>`().
       def message = "message"
//         ^^^^^^^ definition local0
       // reflective invocation
     }.message
//     ^^^^^^^ reference local0

   }
