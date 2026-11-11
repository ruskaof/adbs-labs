package energosbyt.repository;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import energosbyt.config.ClickHouseConfig;
import energosbyt.config.ClickHouseConfiguration;
import energosbyt.config.JacksonConfiguration;
import energosbyt.dto.Owner;
import energosbyt.dto.Reading;
import energosbyt.exception.DatabaseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnergosbytRepositoryIntegrationTest {
    private final String database = "energosbyt_test_" + UUID.randomUUID().toString().replace("-", "");
    private Client adminClient;
    private Client client;
    private ClickHouseQueries admin;
    private ClickHouseQueries queries;
    private EnergosbytRepository repository;
    private boolean databaseCreated;

    @BeforeAll
    void createDatabase() throws Exception {
        var environment = System.getenv();
        var settings = new ClickHouseConfig(environment.get("CLICKHOUSE_HOST"),
                Integer.parseInt(environment.get("CLICKHOUSE_HTTP_PORT")), environment.get("CLICKHOUSE_DATABASE"),
                environment.get("CLICKHOUSE_USER"), environment.getOrDefault("CLICKHOUSE_PASSWORD", ""));
        var configuration = new ClickHouseConfiguration();
        var json = new JacksonConfiguration().jsonMapper();
        adminClient = configuration.clickHouseClient(settings);
        admin = new ClickHouseQueries(adminClient, json);
        admin.execute("CREATE DATABASE " + database);
        databaseCreated = true;
        client = configuration.clickHouseClient(new ClickHouseConfig(settings.host(), settings.httpPort(), database,
                settings.user(), settings.password()));
        queries = new ClickHouseQueries(client, json);
        repository = new EnergosbytRepository(queries);
        for (String statement : Files.readString(Path.of("sql/schema.sql")).split(";")) {
            if (!statement.isBlank()) {
                queries.execute(statement);
            }
        }
    }

    @AfterAll
    void removeDatabase() throws Exception {
        try {
            if (databaseCreated) {
                admin.execute("DROP DATABASE " + database + " SYNC");
            }
        } finally {
            try {
                if (client != null) {
                    client.close();
                }
            } finally {
                if (adminClient != null) {
                    adminClient.close();
                }
            }
        }
    }

    @BeforeEach
    void seedData() {
        for (String table : new String[]{"dim_realty", "dim_gauges", "dim_tariffs", "dim_users",
                "fact_gauge_bindings", "fact_meter_readings", "fact_invoice"}) {
            queries.execute("TRUNCATE TABLE " + table);
        }
        queries.execute("""
                INSERT INTO dim_users VALUES (1, 'Name', 'phone', '', '2023-11-01', '2023-11-01'),
                (18446744073709551615, 'Maximum id', 'phone', '', '2023-11-01', '2023-11-01')
                """);
        queries.execute("INSERT INTO dim_realty VALUES (1, 'Address', 50, 'flat', '1', 2, 1, 1, '2023-11-01')");
        queries.execute("INSERT INTO dim_gauges VALUES (1, 'Old', 'Russia', '2023-11-01'), (2, 'New', 'Russia', '2023-11-01')");
        queries.execute("""
                INSERT INTO dim_tariffs VALUES
                (1, 'Tariff', 540, 270, '2023-11-01', '2025-11-01', '2023-11-01', '2023-11-01'),
                (1, 'Tariff', 620, 310, '2025-11-01', '2099-01-01', '2025-11-01', '2025-11-01')
                """);
        queries.execute("INSERT INTO fact_gauge_bindings VALUES (1, 1, '2023-11-01', 1, 1)");
        queries.execute("""
                INSERT INTO fact_invoice VALUES
                (1, 1, 1, 1, 10, 5, 6750, '2025-11-10', '2025-10-31 23:59:59'),
                (1, 1, 1, 1, 10, 5, 7750, '2025-12-10', '2025-11-30 23:59:59')
                """);
    }

    @Test
    void readsUnsignedIdentifiersWithoutLosingPrecision() {
        var maximum = new BigInteger("18446744073709551615");
        assertThat(repository.owner(maximum)).contains(new Owner(maximum, "Maximum id", "phone", ""));
        assertThat(repository.realty(BigInteger.ONE)).isPresent();
        assertThat(repository.realtyExists(BigInteger.TWO)).isFalse();
    }

    @Test
    void updatesOwnerTextAndHistorySynchronously() {
        var owner = new Owner(BigInteger.ONE, "Иван O'Neil\\line\nnext\tстрока", "+7'\\123", "old phone");
        repository.updateOwner(owner);
        assertThat(repository.owner(BigInteger.ONE)).contains(owner);
        assertThat(queries.execute("SELECT count() FROM dim_users WHERE id = 1 AND fio = 'Name'")).isEqualTo("0");
    }

    @Test
    void correctsReadingsWithTypedDatesAndFullUnsignedCounters() {
        var day = LocalDate.of(2026, 10, 1);
        var first = new Reading(day.toString(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, 5L, 2L);
        var corrected = new Reading(day.toString(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, 4294967295L, 3L);
        assertThat(repository.readingReferencesExist(BigInteger.ONE, first)).isTrue();
        repository.recordReading(BigInteger.ONE, day, first);
        repository.recordReading(BigInteger.ONE, day, corrected);
        assertThat(repository.readings(BigInteger.ONE, day, day)).containsExactly(corrected);
    }

    @Test
    void replacesMeterWithOneActiveBinding() {
        repository.installGauge(BigInteger.ONE, BigInteger.TWO);
        assertThat(queries.execute("SELECT gauge_id, sign, version FROM fact_gauge_bindings FINAL"))
                .isEqualTo("2\t1\t2");
        repository.installGauge(BigInteger.ONE, BigInteger.ONE);
        assertThat(queries.execute("SELECT gauge_id, sign, version FROM fact_gauge_bindings FINAL"))
                .isEqualTo("1\t1\t3");
    }

    @Test
    void installsFirstMeterWithVersionOne() {
        queries.execute("TRUNCATE TABLE fact_gauge_bindings");
        repository.installGauge(BigInteger.ONE, BigInteger.TWO);
        assertThat(queries.execute("SELECT gauge_id, sign, version FROM fact_gauge_bindings FINAL"))
                .isEqualTo("2\t1\t1");
    }

    @Test
    void preservesHistoricalPricesAndPagination() {
        assertThat(repository.tariff(LocalDate.of(2025, 10, 31)).orElseThrow().priceDayKwph()).isEqualTo(540);
        assertThat(repository.tariff(LocalDate.of(2025, 11, 1)).orElseThrow().priceDayKwph()).isEqualTo(620);
        assertThat(repository.invoiceCount(BigInteger.ONE)).isEqualTo(2);
        assertThat(repository.invoices(BigInteger.ONE, 1, 0)).singleElement()
                .satisfies(invoice -> assertThat(invoice.price()).isEqualTo(BigInteger.valueOf(7750)));
        assertThat(repository.invoices(BigInteger.ONE, 1, 1)).singleElement()
                .satisfies(invoice -> assertThat(invoice.price()).isEqualTo(BigInteger.valueOf(6750)));
    }

    @Test
    void reportsDatabaseErrorsAndReleasesConnections() {
        assertThatThrownBy(() -> queries.execute("SELECT * FROM missing_table"))
                .isInstanceOf(DatabaseException.class);
        for (int i = 0; i < 25; i++) {
            assertThat(queries.execute("SELECT {value:UInt32}", Map.of("value", i))).isEqualTo(Integer.toString(i));
        }
    }
}
