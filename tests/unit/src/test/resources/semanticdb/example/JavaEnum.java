   package example;
//         ^^^^^^^ reference example/

   public enum JavaEnum {
//             ^^^^^^^^ definition example/JavaEnum#
     A(1),
//   ^ definition example/JavaEnum#A.
//   ^ reference example/JavaEnum#`<init>`().
     B(2);
//   ^ definition example/JavaEnum#B.
//   ^ reference example/JavaEnum#`<init>`().

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
