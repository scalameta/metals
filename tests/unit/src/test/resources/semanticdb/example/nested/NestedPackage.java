   // Just to test handling of `example.nested`
   package example.nested;
//         ^^^^^^^ reference example/
//                 ^^^^^^ reference example/nested/

   public class NestedPackage {
//              ^^^^^^^^^^^^^ definition example/nested/NestedPackage#
//              ^^^^^^^^^^^^^ definition example/nested/NestedPackage#`<init>`().
     public static void a() {}
//                      ^ definition example/nested/NestedPackage#a().
   }
