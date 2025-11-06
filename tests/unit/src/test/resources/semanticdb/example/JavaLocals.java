   package example;
//         ^^^^^^^ reference example/

   import java.util.Scanner;
//        ^^^^ reference java/
//             ^^^^ reference java/util/
//                  ^^^^^^^ reference java/util/Scanner#
   import java.util.stream.Stream;
//        ^^^^ reference java/
//             ^^^^ reference java/util/
//                  ^^^^^^ reference java/util/stream/
//                         ^^^^^^ reference java/util/stream/Stream#

   public class JavaLocals {
//              ^^^^^^^^^^ definition example/JavaLocals#
//              ^^^^^^^^^^ definition example/JavaLocals#`<init>`().
     record Point(int x, int y) {}
//          ^^^^^ definition example/JavaLocals#Point#
//          ^^^^^ definition example/JavaLocals#Point#`<init>`().
//                    ^ definition local0
//                    ^ definition example/JavaLocals#Point#x().
//                           ^ definition local1
//                           ^ definition example/JavaLocals#Point#y().

     public int test(int x) {
//              ^^^^ definition example/JavaLocals#test().
//                       ^ definition local2
       int a = 1;
//         ^ definition local3
       int b = x;
//         ^ definition local4
//             ^ reference local2
       if (b > 0) {
//         ^ reference local4
         var c = a + b;
//           ^ definition local5
//               ^ reference local3
//                   ^ reference local4
         return c;
//              ^ reference local5
       }
       Object p = new Point(1, 2);
//     ^^^^^^ reference java/lang/Object#
//            ^ definition local6
//                    ^^^^^ reference example/JavaLocals#Point#`<init>`().
       // TODO: instanceof binding not handled yet
       if (p instanceof Point p2) {
//         ^ reference local6
//                      ^^^^^ reference example/JavaLocals#Point#
//                            ^^ definition local7
         a += p2.x();
//       ^ reference local3
//            ^^ reference local7
//               ^ reference example/JavaLocals#Point#x().
         a += p2.y();
//       ^ reference local3
//            ^^ reference local7
//               ^ reference example/JavaLocals#Point#y().
       }
       try {
         for (int i = 0; i < 10; i++) {
//                ^ definition local8
//                       ^ reference local8
//                               ^ reference local8
           a += i;
//         ^ reference local3
//              ^ reference local8
         }
       } catch (Exception e) {
//              ^^^^^^^^^ reference java/lang/Exception#
//                        ^ definition local9
         a += e.getMessage().length();
//       ^ reference local3
//            ^ reference local9
//              ^^^^^^^^^^ reference java/lang/Throwable#getMessage().
//                           ^^^^^^ reference java/lang/String#length().
       }
       for (int e : new int[] {1, 2, 3}) {
//              ^ definition local10
         a += e;
//       ^ reference local3
//            ^ reference local10
       }
       try (var s = new Scanner(System.in)) {
//              ^ definition local11
//                      ^^^^^^^ reference java/util/Scanner#`<init>`(+2).
//                              ^^^^^^ reference java/lang/System#
//                                     ^^ reference java/lang/System#in.
         a += s.nextInt();
//       ^ reference local3
//            ^ reference local11
//              ^^^^^^^ reference java/util/Scanner#nextInt().
       }
       return Stream.of(a, b).map(i -> i * 2).reduce(0, (i, j) -> i + j);
//            ^^^^^^ reference java/util/stream/Stream#
//                   ^^ reference java/util/stream/Stream#of(+1).
//                      ^ reference local3
//                         ^ reference local4
//                            ^^^ reference java/util/stream/Stream#map().
//                                ^ definition local12
//                                     ^ reference local12
//                                            ^^^^^^ reference java/util/stream/Stream#reduce().
//                                                       ^ definition local13
//                                                          ^ definition local14
//                                                                ^ reference local13
//                                                                    ^ reference local14
     }
   }
