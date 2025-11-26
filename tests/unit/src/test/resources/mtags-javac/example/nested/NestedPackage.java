   // Just to test handling of `example.nested`
   package example.nested;
//         ^^^^^^ reference nested/
//         ^^^^^^^ reference example/
//                 ^^^^^^ reference example/nested/

   public class NestedPackage {
//              ^^^^^^^^^^^^^ definition example/nested/NestedPackage# CLASS
//              ^^^^^^^^^^^^^ definition example/nested/NestedPackage#`<init>`(). CONSTRUCTOR
     public static void a() {}
//                      ^ definition example/nested/NestedPackage#a(). METHOD
   }