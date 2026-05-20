package example

class SQLQueries/*example.SQLQueries#*/ {
    implicit class SQLStringContext/*example.SQLQueries#SQLStringContext().*//*example.SQLQueries#SQLStringContext#*/(sc/*example.SQLQueries#SQLStringContext#sc.*/: StringContext) {
        def sql/*example.SQLQueries#SQLStringContext#sql().*/(args/*example.SQLQueries#SQLStringContext#sql().(args)*/: Any*): String = sc.s(args: _*)
    }

    val createTableQuery/*example.SQLQueries#createTableQuery.*/ = sql"""
        CREATE TABLE users (
            id INT PRIMARY KEY,
            name VARCHAR(100),
            age DECIMAL(3, 1),
            created_at TIMESTAMP
        )
        """

    val selectQuery/*example.SQLQueries#selectQuery.*/ = sql"""
        SELECT name, age
        FROM users
        WHERE age > 30.5
        """

    val insertQuery/*example.SQLQueries#insertQuery.*/ = sql"""
        INSERT INTO users (id, name, age, created_at)
        VALUES (1, 'John Doe', 25, CURRENT_TIMESTAMP)
        """

    val nestedInterpolators/*example.SQLQueries#nestedInterpolators.*/ = sql"""
        SELECT name, age
        ${s"FROM ${sql"users"} WHERE"} age > 30.5
        """
}