   package example
//         ^^^^^^^ definition example/

   package nested.x {
//         ^^^^^^ reference example/nested/
//                ^ reference example/nested/x/
     class DoublePackage {}
//         ^^^^^^^^^^^^^ definition example/nested/x/DoublePackage#
//                       ^ definition example/nested/x/DoublePackage#`<init>`().
   }

   package nested2.y {
//         ^^^^^^^ reference example/nested2/
//                 ^ reference example/nested2/y/
     class DoublePackage {}
//         ^^^^^^^^^^^^^ definition example/nested2/y/DoublePackage#
//                       ^ definition example/nested2/y/DoublePackage#`<init>`().
   }
