package de.atstck.kitly.cal;

import com.zaxxer.hikari.HikariDataSource;
import de.atstck.kitly.cal.store.BookingStore;
import de.atstck.kitly.cal.store.JdbcBookingStore;
import de.atstck.kitly.cal.support.TestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The store contract against a real PostgreSQL, with real parallel connections. Skipped without a database. */
class JdbcBookingStoreTest extends StoreContract {

    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static JdbcBookingStore store;

    @BeforeAll
    static void connect() {
        dataSource = TestDatabase.dataSource();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcBookingStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                JsonMapper.builder().build());
    }

    @AfterAll
    static void disconnect() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void empty() {
        jdbc.sql("truncate outbox, bookings restart identity cascade").update();
    }

    @Override
    BookingStore store() {
        return store;
    }
}
