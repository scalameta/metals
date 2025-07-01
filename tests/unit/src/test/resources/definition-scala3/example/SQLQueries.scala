package example

class SQLQueries/*SQLQueries.scala*/ {
    implicit class SQLStringContext/*SQLQueries.scala*/(sc/*SQLQueries.scala*/: StringContext/*StringContext.scala*/) {
        def sql/*SQLQueries.scala*/(args/*SQLQueries.scala*/: Any/*Any.scala*/*/*<no symbol>*/): String/*Predef.scala*/ = sc/*SQLQueries.scala*/.s/*StringContext.scala fallback to scala.StringContext#*/(args/*SQLQueries.scala*/: _*/*<no symbol>*/)
    }

    val createTableQuery/*SQLQueries.scala*/ = sql/*SQLQueries.scala*/"""
        CREATE TABLE users (
            id INT PRIMARY KEY,
            name VARCHAR(100),
            age DECIMAL(3, 1),
            created_at TIMESTAMP
        )
        """

    val selectQuery/*SQLQueries.scala*/ = sql/*SQLQueries.scala*/"""
        SELECT name, age
        FROM users
        WHERE age > 30.5
        """

    val insertQuery/*SQLQueries.scala*/ = sql/*SQLQueries.scala*/"""
        INSERT INTO users (id, name, age, created_at)
        VALUES (1, 'John Doe', 25, CURRENT_TIMESTAMP)
        """
}