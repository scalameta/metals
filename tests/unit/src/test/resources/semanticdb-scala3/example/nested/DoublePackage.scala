   package example
//         ^^^^^^^ definition example/

   package nested.x {
//         ^^^^^^ reference example/nested/
//                ^ definition example/nested/x/
     class DoublePackage {}
//   ^ definition example/nested/x/DoublePackage#`<init>`().
//         ^^^^^^^^^^^^^ definition example/nested/x/DoublePackage#
   }

   package nested2.y {
//         ^^^^^^^ reference example/nested2/
//                 ^ definition example/nested2/y/
     class DoublePackage {}
//   ^ definition example/nested2/y/DoublePackage#`<init>`().
//         ^^^^^^^^^^^^^ definition example/nested2/y/DoublePackage#
   }
