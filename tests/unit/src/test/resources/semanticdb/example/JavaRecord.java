   package example;
//         ^^^^^^^ reference example/

   public record JavaRecord(int a, int b) {
//               ^^^^^^^^^^ definition example/JavaRecord#
//               ^^^^^^^^^^ definition example/JavaRecord#`<init>`(+2).
//                              ^ definition local0
//                              ^ definition example/JavaRecord#a.
//                                     ^ definition local1
//                                     ^ definition example/JavaRecord#b.
    public JavaRecord(int a) {
//         ^^^^^^^^^^ definition example/JavaRecord#`<init>`().
//                        ^ definition local2
     this(a, 0);
//   ^^^^ reference example/JavaRecord#`<init>`(+2).
//        ^ reference local2
    }

    public JavaRecord(int a, int b, int c) {
//         ^^^^^^^^^^ definition example/JavaRecord#`<init>`(+1).
//                        ^ definition local3
//                               ^ definition local4
//                                      ^ definition local5
     this(a, b + c);
//   ^^^^ reference example/JavaRecord#`<init>`(+2).
//        ^ reference local3
//           ^ reference local4
//               ^ reference local5
    }

    public static JavaRecord of(int a) {
//                ^^^^^^^^^^ reference example/JavaRecord#
//                           ^^ definition example/JavaRecord#of().
//                                  ^ definition local6
     return new JavaRecord(a);
//              ^^^^^^^^^^ reference example/JavaRecord#`<init>`().
//                         ^ reference local6
    }

    public int sum() {
//             ^^^ definition example/JavaRecord#sum().
     return a + b;
//          ^ reference example/JavaRecord#a.
//              ^ reference example/JavaRecord#b.
    }
   }