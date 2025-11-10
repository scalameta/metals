   package example;
//         ^^^^^^^ reference example/

   public enum JavaEnum {
//             ^^^^^^^^ definition example/JavaEnum#
     A(1),
//   ^ definition example/JavaEnum#A.
//   ^ reference example/JavaEnum#`<init>`().
     B(2),
//   ^ definition example/JavaEnum#B.
//   ^ reference example/JavaEnum#`<init>`().
     C(Magic.E);
//   ^ definition example/JavaEnum#C.
//   ^ reference example/JavaEnum#`<init>`().
//     ^^^^^ reference example/JavaEnum#Magic#
//           ^ reference example/JavaEnum#Magic#E.

     public static final JavaEnum D = B;
//                       ^^^^^^^^ reference example/JavaEnum#
//                                ^ definition example/JavaEnum#D.
//                                    ^ reference example/JavaEnum#B.

     public static class Magic {
//                       ^^^^^ definition example/JavaEnum#Magic#
//                       ^^^^^ definition example/JavaEnum#Magic#`<init>`().
       public static final int E = 42;
//                             ^ definition example/JavaEnum#Magic#E.
     }

     JavaEnum(int d) {
//   ^^^^^^^^ definition example/JavaEnum#`<init>`().
//                ^ definition local0
       this.d = d;
//          ^ reference example/JavaEnum#d.
//              ^ reference local0
     }

     public int d = 2;
//              ^ definition example/JavaEnum#d.
   }
