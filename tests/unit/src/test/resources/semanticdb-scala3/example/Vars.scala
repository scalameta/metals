   package example
//         ^^^^^^^ definition example/

   object Vars {
//        ^^^^ definition example/Vars.
     var a = 2
//       ^ definition example/Vars.a().

     a = 2
//   ^ reference example/Vars.`a_=`().

     Vars.a = 3
//   ^^^^ reference example/Vars.
//        ^ reference example/Vars.`a_=`().
   }
