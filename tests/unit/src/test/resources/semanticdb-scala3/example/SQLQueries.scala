   package example
//         ^^^^^^^ definition example/

   class SQLQueries {
//       ^^^^^^^^^^ definition example/SQLQueries#
       implicit class SQLStringContext(sc: StringContext) {
//     ^ definition example/SQLQueries#`<init>`().
//                    ^^^^^^^^^^^^^^^^ definition example/SQLQueries#SQLStringContext#
//                                    ^ definition example/SQLQueries#SQLStringContext#`<init>`().
//                                     ^^ definition example/SQLQueries#SQLStringContext#sc.
//                                         ^^^^^^^^^^^^^ reference scala/StringContext#
           def sql(args: Any*): String = sc.s(args: _*)
//             ^^^ definition example/SQLQueries#SQLStringContext#sql().
//                 ^^^^ definition example/SQLQueries#SQLStringContext#sql().(args)
//                       ^^^ reference scala/Any#
//                              ^^^^^^ reference scala/Predef.String#
//                                       ^^ reference example/SQLQueries#SQLStringContext#sc.
//                                          ^ reference scala/StringContext#s().
//                                            ^^^^ reference example/SQLQueries#SQLStringContext#sql().(args)
       }

       val createTableQuery = sql"""
//         ^^^^^^^^^^^^^^^^ definition example/SQLQueries#createTableQuery.
//                            ^^^ reference example/SQLQueries#SQLStringContext#sql().
           CREATE TABLE users (
               id INT PRIMARY KEY,
               name VARCHAR(100),
               age DECIMAL(3, 1),
               created_at TIMESTAMP
           )
           """

       val selectQuery = sql"""
//         ^^^^^^^^^^^ definition example/SQLQueries#selectQuery.
//                       ^^^ reference example/SQLQueries#SQLStringContext#sql().
           SELECT name, age
           FROM users
           WHERE age > 30.5
           """

       val insertQuery = sql"""
//         ^^^^^^^^^^^ definition example/SQLQueries#insertQuery.
//                       ^^^ reference example/SQLQueries#SQLStringContext#sql().
           INSERT INTO users (id, name, age, created_at)
           VALUES (1, 'John Doe', 25, CURRENT_TIMESTAMP)
           """

       val nestedInterpolators = sql"""
//         ^^^^^^^^^^^^^^^^^^^ definition example/SQLQueries#nestedInterpolators.
//                               ^^^ reference example/SQLQueries#SQLStringContext#sql().
           SELECT name, age
           ${s"FROM ${sql"users"} WHERE"} age > 30.5
//           ^ reference scala/StringContext#s().
//                    ^^^ reference example/SQLQueries#SQLStringContext#sql().
           """
   }