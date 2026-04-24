   package example
//         ^^^^^^^ definition example/

   def foo(): Int = 42
//     ^^^ definition example/ToplevelDefVal$package.foo().
//            ^^^ reference scala/Int#

   val abc: String = "sds"
//     ^^^ definition example/ToplevelDefVal$package.abc.
//          ^^^^^^ reference scala/Predef.String#

   // tests jar's indexing on Windows
   type SourceToplevelTypeFromDepsRef = EmptyTuple
//      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ definition example/ToplevelDefVal$package.SourceToplevelTypeFromDepsRef#
//                                      ^^^^^^^^^^ reference scala/Tuple$package.EmptyTuple#
