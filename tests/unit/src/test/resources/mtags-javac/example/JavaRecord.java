   package example;
//         ^^^^^^^ reference example/

   public record JavaRecord(int a, int b) {
//               ^^^^^^^^^^ definition example/JavaRecord# CLASS
//               ^^^^^^^^^^ definition example/JavaRecord#`<init>`(+2). CONSTRUCTOR
//                              ^ definition example/JavaRecord#a(). METHOD
//                                     ^ definition example/JavaRecord#b(). METHOD
     public JavaRecord(int a) {
//          ^^^^^^^^^^ definition example/JavaRecord#`<init>`(). CONSTRUCTOR
       this(a, 0);
     }

     public JavaRecord(int a, int b, int c) {
//          ^^^^^^^^^^ definition example/JavaRecord#`<init>`(+1). CONSTRUCTOR
       this(a, b + c);
     }

     public static JavaRecord of(int a) {
//                 ^^^^^^^^^^ reference JavaRecord.
//                            ^^ definition example/JavaRecord#of(). METHOD
       return new JavaRecord(a);
//                ^^^^^^^^^^ reference JavaRecord.
     }

     public int sum() {
//              ^^^ definition example/JavaRecord#sum(). METHOD
       return a + b;
//            ^ reference a.
//                ^ reference b.
     }
   }