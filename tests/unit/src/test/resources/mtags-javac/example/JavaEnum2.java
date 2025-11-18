   package example;
//         ^^^^^^^ reference example/

   public class JavaEnum2 {
//              ^^^^^^^^^ definition example/JavaEnum2# CLASS
//              ^^^^^^^^^ definition example/JavaEnum2#`<init>`(). CONSTRUCTOR
     enum Day {
//        ^^^ definition example/JavaEnum2#Day# CLASS(enum)
//        ^^^ definition example/JavaEnum2#Day#`<init>`(). CONSTRUCTOR
       WORKDAY,
//     ^^^^^^^ definition example/JavaEnum2#Day#WORKDAY. FIELD(enum)
       WEEKEND
//     ^^^^^^^ definition example/JavaEnum2#Day#WEEKEND. FIELD(enum)
     }
   }