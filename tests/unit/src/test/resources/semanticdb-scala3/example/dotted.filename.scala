   package example
//         ^^^^^^^ definition example/
// ^^^^^^^^^^^^^^^!2:19 diagnostic - warning The package name `dotted.filename$package` will be encoded on the classpath, and can lead to undefined behaviour.

   type Toplevel = Int
//      ^^^^^^^^ definition example/`dotted.filename$package`.Toplevel#
//                 ^^^ reference scala/Int#
