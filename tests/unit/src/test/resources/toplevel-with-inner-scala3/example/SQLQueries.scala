package example

class SQLQueries/*example.SQLQueries#*/ {
    implicit class SQLStringContext/*example.SQLQueries#SQLStringContext#*/(sc: StringContext) {
        def sql(args: Any*): String = sc.s(args: _*)
    }

    val createTableQuery = sql"""
        CREATE TABLE users (
            id INT PRIMARY KEY,
            name VARCHAR(100),
            age DECIMAL(3, 1),
            created_at TIMESTAMP
        )
        """

    val selectQuery = sql"""
        SELECT name, age
        FROM users
        WHERE age > 30.5
        """

    val insertQuery = sql"""
        INSERT INTO users (id, name, age, created_at)
        VALUES (1, 'John Doe', 25, CURRENT_TIMESTAMP)
        """
}