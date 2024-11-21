package gcc2speedscope

import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

abstract class DataStore(database: String): AutoCloseable {
    private
    val conn = DriverManager.getConnection("jdbc:h2:$database", "sa", "")

    override fun close() {
        conn.close()
    }

    protected
    fun PreparedStatement.insertAndGetGeneratedKey(stringParam: String): Long = run {
        setString(1, stringParam)
        executeUpdate()
        generatedKeys.use { rs ->
            rs.firstLong()!!
        }
    }

    protected
    fun PreparedStatement.queryLong(stringParam: String): Long? = run {
        setString(1, stringParam)
        executeQuery().use { rs ->
            rs.firstLong()
        }
    }

    protected
    fun ResultSet.firstLong() =
        takeIf { it.next() }?.getLong(1)

    protected
    inline fun forEachIn(query: String, action: ResultSet.() -> Unit) {
        resultSet(query).use { rs ->
            while (rs.next()) {
                action(rs)
            }
        }
    }

    protected
    fun resultSet(query: String): ResultSet =
        createStatement().executeQuery(query)

    protected
    fun createStatement(): Statement =
        conn.createStatement()

    protected
    fun prepareInsert(sql: String): PreparedStatement =
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)

    protected
    fun prepare(sql: String): PreparedStatement =
        conn.prepareStatement(sql)
}
