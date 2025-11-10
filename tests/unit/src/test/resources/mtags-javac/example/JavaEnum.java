   package example;
//         ^^^^^^^ reference example/

   public enum JavaEnum {
//             ^^^^^^^^ definition example/JavaEnum# CLASS(enum)
     A(1),
//   ^ definition example/JavaEnum#A. FIELD(enum)
     B(2),
//   ^ definition example/JavaEnum#B. FIELD(enum)
     C(Magic.E);
//   ^ definition example/JavaEnum#C. FIELD(enum)
//     ^ reference E.
//     ^^^^^ reference Magic.

     public static final JavaEnum D = B;
//                       ^^^^^^^^ reference JavaEnum.
//                                ^ definition example/JavaEnum#D. FIELD
//                                    ^ reference B.

     public static class Magic {
//                       ^^^^^ definition example/JavaEnum#Magic# CLASS
//                       ^^^^^ definition example/JavaEnum#Magic#`<init>`(). CONSTRUCTOR
       public static final int E = 42;
//                             ^ definition example/JavaEnum#Magic#E. FIELD
     }

     JavaEnum(int d) {
//   ^^^^^^^^ definition example/JavaEnum#`<init>`(). CONSTRUCTOR
       this.d = d;
     }

     public int d = 2;
//              ^ definition example/JavaEnum#d. FIELD
   }