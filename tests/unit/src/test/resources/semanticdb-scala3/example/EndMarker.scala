   package example
//         ^^^^^^^ definition example/

   object EndMarker:
//        ^^^^^^^^^ definition example/EndMarker.
     def foo =
//       ^^^ definition example/EndMarker.foo().
       1
     end foo
//       ^^^ reference example/EndMarker.foo().
