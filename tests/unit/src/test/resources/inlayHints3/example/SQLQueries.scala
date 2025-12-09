package example

class SQLQueries {
    implicit class SQLStringContext(sc: StringContext) {
        def sql(args: Any*): String = sc.s(/*args = */args: _*)
    }

    val createTableQuery/*: String<<scala/Predef.String#>>*/ = sql"""
        CREATE TABLE users (
            id INT PRIMARY KEY,
            name VARCHAR(100),
            age DECIMAL(3, 1),
            created_at TIMESTAMP
        )
        """

    val selectQuery/*: String<<scala/Predef.String#>>*/ = sql"""
        SELECT name, age
        FROM users
        WHERE age > 30.5
        """

    val insertQuery/*: String<<scala/Predef.String#>>*/ = sql"""
        INSERT INTO users (id, name, age, created_at)
        VALUES (1, 'John Doe', 25, CURRENT_TIMESTAMP)
        """
}