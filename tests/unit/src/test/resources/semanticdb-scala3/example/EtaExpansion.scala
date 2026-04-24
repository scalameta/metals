   package example
//         ^^^^^^^ definition example/

   class EtaExpansion {
//       ^^^^^^^^^^^^ definition example/EtaExpansion#
     List(1).map(identity)
//   ^ definition example/EtaExpansion#`<init>`().
//   ^^^^ reference scala/package.List.
//           ^^^ reference scala/collection/immutable/List#map().
//               ^^^^^^^^ reference scala/Predef.identity().
   }
