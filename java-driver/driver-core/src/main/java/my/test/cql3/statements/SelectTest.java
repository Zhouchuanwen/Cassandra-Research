package my.test.cql3.statements;

import my.test.TestBase;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.SimpleStatement;

public class SelectTest extends TestBase {
    public static void main(String[] args) throws Exception {
        new SelectTest().start();
    }

    @Override
    public void startInternal() throws Exception {
        //tableName = "SelectTest";
        //create();
        //insert();
        test();
    }

    void create() {
        session.execute("DROP TABLE IF EXISTS users");
        session.execute("CREATE TABLE IF NOT EXISTS users " + "(user_id text, f1 int, age int, first_name text,last_name text,"
                + "emails set<text>, top_places list<text>, todo map<timestamp, text>, PRIMARY KEY ((user_id,f1),age))");
        cql = "CREATE INDEX IF NOT EXISTS users_age ON users (age)";
        session.execute(cql);
    }

    void insert() {

        session.execute("INSERT INTO users (user_id, f1, age, first_name, last_name, emails) "
                + "VALUES('frodo', 11, 20, 'Frodo', 'Baggins', {'f@baggins.com','baggins@gmail.com'});");
        session.execute("INSERT INTO users (user_id, f1, age, first_name, last_name, emails) "
                + "VALUES('frodo', 12, 10, 'Frodo', 'Baggins', {'f@baggins.com','baggins@gmail.com'});");
        session.execute("INSERT INTO users (user_id, f1, age, first_name, last_name, emails) "
                + "VALUES('frodo', 13, 30, 'Frodo', 'Baggins', {'f@baggins.com','baggins@gmail.com'});");

        for (int i = 30; i < 100; i++)
            session.execute("INSERT INTO users (user_id, f1, age, first_name, last_name, emails) " //
                    + "VALUES('frodo', 11, " + i + ", 'Frodo', 'Baggins', {'f@baggins.com','baggins@gmail.com'});");

    }

    public void test() {

        cql = "SELECT * FROM users WHERE user_id = 'frodo'";
        //错误:Cannot use selection function writeTime on PRIMARY KEY part user_id
        cql = "SELECT WRITETIME(user_id), TTL(emails) FROM users WHERE user_id = 'frodo'";
        //错误:Cannot use selection function ttl on collections
        cql = "SELECT WRITETIME(first_name), TTL(emails) FROM users WHERE user_id = 'frodo'";
        cql = "SELECT user_id, emails, WRITETIME(first_name), TTL(last_name),token(user_id) FROM users WHERE user_id = 'frodo'";
        cql = "SELECT user_id, age, emails, WRITETIME(first_name), TTL(last_name),token(user_id) FROM users "
                + "WHERE user_id = 'frodo' ORDER BY age DESC";

        cql = "SELECT count(*) as c FROM users WHERE user_id = 'frodo'";
        cql = "SELECT DISTINCT first_name FROM users WHERE user_id = 'frodo'";
        cql = "SELECT * FROM users WHERE user_id = 'frodo' LIMIT 2";

        //错误: Aliases aren't allowed in where clause ('a EQ 10')
        cql = "SELECT age as a FROM users WHERE user_id = 'frodo' and a=10";
        //错误: Undefined name undefined in where clause ('undefined EQ 10')
        cql = "SELECT age as a FROM users WHERE user_id = 'frodo' and undefined=10";

        cql = "SELECT * FROM users WHERE user_id = 'frodo' and age=10";
        //错误: user_id cannot be restricted by more than one relation if it includes an Equal
        cql = "SELECT * FROM users WHERE user_id = 'frodo' and age=10 and user_id = 'frodo2'";
        cql = "SELECT * FROM users WHERE user_id in('frodo','frodo2') and age=10";

        cql = "SELECT * FROM users WHERE user_id in('frodo','frodo2') and age=10 and last_name>'a'";

        //错误: IN predicates on non-primary-key columns (last_name) is not yet supported
        cql = "SELECT * FROM users WHERE user_id = 'frodo' and last_name in('frodo','frodo2')";
        //in只支持一个值
        cql = "SELECT * FROM users WHERE user_id = 'frodo' and last_name in('frodo')";

        cql = "SELECT token(user_id) FROM users WHERE age=10";
        cql = "SELECT token(user_id, f1) FROM users WHERE user_id = 'frodo' and age=10";

        cql = "SELECT * FROM users WHERE age>=10 ALLOW FILTERING";
        cql = "SELECT * FROM users WHERE user_id = 'frodo' and f1=11 and age>=10 ALLOW FILTERING";

        SimpleStatement stmt = new SimpleStatement(cql);
        stmt.setFetchSize(20);
        ResultSet results = session.execute(stmt);
        printResultSet(results);
    }
}
