   package example
//         ^^^^^^^ definition example/

   class EtaExpansion {
//       ^^^^^^^^^^^^ definition example/EtaExpansion#
//                    ^ definition example/EtaExpansion#`<init>`().
     List(1).map(identity)
//   ^^^^ reference scala/package.List.
//           ^^^ reference scala/collection/immutable/List#map().
//               ^^^^^^^^ reference scala/Predef.identity().
   }
