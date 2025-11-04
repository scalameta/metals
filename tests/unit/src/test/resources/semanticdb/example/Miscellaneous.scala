   package example
//         ^^^^^^^ definition example/

   class Miscellaneous {
//       ^^^^^^^^^^^^^ definition example/Miscellaneous#
//                     ^ definition example/Miscellaneous#`<init>`().
     // backtick identifier
     val `a b` = 42
//       ^^^^^ definition example/Miscellaneous#`a b`.

     // block with only wildcard value
     def apply(): Unit = {
//       ^^^^^ definition example/Miscellaneous#apply().
//                ^^^^ reference scala/Unit#
       val _ = 42
     }
     // infix + inferred apply/implicits/tparams
     (List(1)
//    ^^^^ reference scala/package.List.
       .map(_ + 1)
//      ^^^ reference scala/collection/immutable/List#map().
//            ^ reference scala/Int#`+`(+4).
       ++
//     ^^ reference scala/collection/IterableOps#`++`().
         List(3))
//       ^^^^ reference scala/package.List.
   }
