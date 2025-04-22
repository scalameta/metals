/*example(Package):27*/package example

/*example.SQLQueries(Class):27*/class SQLQueries {
    /*example.SQLQueries#SQLStringContext(Class):6*/implicit class SQLStringContext(sc: StringContext) {
        /*example.SQLQueries#SQLStringContext#sql(Method):5*/def sql(args: Any*): String = sc.s(args: _*)
    }

    /*example.SQLQueries#createTableQuery(Constant):15*/val createTableQuery = sql"""
        CREATE TABLE users (
            id INT PRIMARY KEY,
            name VARCHAR(100),
            age DECIMAL(3, 1),
            created_at TIMESTAMP
        )
        """

    /*example.SQLQueries#selectQuery(Constant):21*/val selectQuery = sql"""
        SELECT name, age
        FROM users
        WHERE age > 30.5
        """

    /*example.SQLQueries#insertQuery(Constant):26*/val insertQuery = sql"""
        INSERT INTO users (id, name, age, created_at)
        VALUES (1, 'John Doe', 25, CURRENT_TIMESTAMP)
        """
}