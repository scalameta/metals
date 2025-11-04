   package example
//         ^^^^^^^ definition example/

   object ExampleSuite {
//        ^^^^^^^^^^^^ definition example/ExampleSuite.
     println(NamedArguments.user2)
//   ^^^^^^^ reference scala/Predef.println(+1).
//           ^^^^^^^^^^^^^^ reference example/NamedArguments.
//                          ^^^^^ reference example/NamedArguments.user2.
   }
