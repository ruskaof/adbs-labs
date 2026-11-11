package energosbyt.repository;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import energosbyt.dto.Invoice;
import energosbyt.dto.Owner;
import energosbyt.dto.Reading;
import energosbyt.dto.Realty;
import energosbyt.dto.Tariff;
import energosbyt.exception.DatabaseException;

@Repository
public class EnergosbytRepository {
    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final long MAX_TIMESTAMP = 0xffff_ffffL;
    private static final BigInteger MAX_ID = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final ClickHouseQueries database;

    public EnergosbytRepository(ClickHouseQueries database) {
        this.database = database;
    }

    public Optional<Owner> owner(BigInteger id) {
        return database.rows("""
                SELECT id, fio, phone, phone_prev
                FROM dim_users FINAL WHERE id = {id:UInt64}
                """, Map.of("id", id), Owner.class).stream().findFirst();
    }

    public void updateOwner(Owner owner) {
        database.execute("""
                ALTER TABLE dim_users UPDATE
                    fio = base64Decode({fio:String}),
                    phone = base64Decode({phone:String}),
                    phone_prev = base64Decode({phone_prev:String})
                WHERE id = {id:UInt64} SETTINGS mutations_sync = 1
                """, Map.of("id", owner.id(), "fio", base64(owner.fio()), "phone", base64(owner.phone()),
                "phone_prev", base64(owner.phonePrev())));
    }

    public Optional<Realty> realty(BigInteger id) {
        return database.rows("""
                SELECT id, address, square, type, number, rooms, floor, porch
                FROM dim_realty WHERE id = {id:UInt64}
                """, Map.of("id", id), Realty.class).stream().findFirst();
    }

    public boolean realtyExists(BigInteger id) {
        return exists("SELECT 1 FROM dim_realty WHERE id = {id:UInt64} LIMIT 1", id);
    }

    public boolean gaugeExists(BigInteger id) {
        return exists("SELECT 1 FROM dim_gauges WHERE id = {id:UInt64} LIMIT 1", id);
    }

    private boolean exists(String sql, BigInteger id) {
        return database.execute(sql, Map.of("id", id)).equals("1");
    }

    public Optional<Tariff> tariff(LocalDate date) {
        long midnight = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        return database.rows("""
                SELECT id, name, price_day_kwph, price_night_kwph,
                       toString(valid_from) AS valid_from, toString(valid_to) AS valid_to
                FROM dim_tariffs FINAL
                WHERE toInt64(toUnixTimestamp(dim_tariffs.valid_from)) <= {midnight:Int64}
                  AND {midnight:Int64} < toInt64(toUnixTimestamp(dim_tariffs.valid_to))
                ORDER BY dim_tariffs.valid_from DESC, id LIMIT 1
                """, Map.of("midnight", midnight), Tariff.class).stream().findFirst();
    }

    public boolean readingReferencesExist(BigInteger realty, Reading reading) {
        return database.execute("""
                SELECT toUInt8(
                    EXISTS(SELECT 1 FROM dim_realty WHERE id = {realty:UInt64}) AND
                    EXISTS(SELECT 1 FROM dim_users WHERE id = {owner:UInt64}) AND
                    EXISTS(SELECT 1 FROM dim_gauges WHERE id = {gauge:UInt64}) AND
                    EXISTS(SELECT 1 FROM dim_tariffs WHERE id = {tariff:UInt64})
                )
                """, Map.of("realty", realty, "owner", reading.userId(), "gauge", reading.gaugeId(),
                "tariff", reading.tariffId())).equals("1");
    }

    public void recordReading(BigInteger realty, LocalDate date, Reading reading) {
        String previous = database.execute("""
                SELECT toUnixTimestamp(created_at) FROM fact_meter_readings
                WHERE realty_id = {realty:UInt64} AND reading_date = {date:Date}
                ORDER BY created_at DESC LIMIT 1
                """, Map.of("realty", realty, "date", date));
        long version = Instant.now().getEpochSecond();
        if (!previous.isEmpty()) {
            version = Math.max(version, Long.parseLong(previous) + 1);
        }
        if (version < 0 || version > MAX_TIMESTAMP) {
            throw new DatabaseException("Reading version outside the ClickHouse DateTime range");
        }
        database.execute("""
                INSERT INTO fact_meter_readings
                    (realty_id, user_id, tariff_id, gauge_id, reading_date, day_kw, night_kw, created_at)
                SELECT {realty:UInt64}, {owner:UInt64}, {tariff:UInt64}, {gauge:UInt64},
                       {date:Date}, {day:UInt32}, {night:UInt32}, toDateTime({version:UInt32})
                """, Map.of("realty", realty, "owner", reading.userId(), "tariff", reading.tariffId(),
                "gauge", reading.gaugeId(), "date", date, "day", reading.dayKw(),
                "night", reading.nightKw(), "version", version));
    }

    public List<Reading> readings(BigInteger realty, LocalDate from, LocalDate to) {
        return database.rows("""
                SELECT toString(reading_date) AS reading_date, user_id, tariff_id, gauge_id, day_kw, night_kw
                FROM fact_meter_readings FINAL
                WHERE realty_id = {realty:UInt64}
                  AND fact_meter_readings.reading_date BETWEEN {from:Date} AND {to:Date}
                ORDER BY fact_meter_readings.reading_date
                """, Map.of("realty", realty, "from", from, "to", to), Reading.class);
    }

    public long invoiceCount(BigInteger realty) {
        return Long.parseLong(database.execute("""
                SELECT count() FROM fact_invoice WHERE realty_id = {realty:UInt64}
                """, Map.of("realty", realty)));
    }

    public List<Invoice> invoices(BigInteger realty, long limit, long offset) {
        return database.rows("""
                SELECT tariff_id, gauge_id, day_kw, night_kw, price,
                       toString(deadline) AS deadline, toString(created_at) AS created_at
                FROM fact_invoice WHERE realty_id = {realty:UInt64}
                ORDER BY fact_invoice.created_at DESC, gauge_id, tariff_id, day_kw, night_kw, price, deadline
                LIMIT {limit:UInt64} OFFSET {offset:UInt64}
                """, Map.of("realty", realty, "limit", limit, "offset", offset), Invoice.class);
    }

    public void installGauge(BigInteger realty, BigInteger gauge) {
        var previous = database.rows("""
                SELECT gauge_id, toString(created_at) AS created_at, version
                FROM fact_gauge_bindings FINAL
                WHERE realty_id = {realty:UInt64} AND sign = 1 ORDER BY version DESC LIMIT 1
                """, Map.of("realty", realty), Binding.class).stream().findFirst();

        String installed = CLICKHOUSE_TIME.format(Instant.now());
        if (previous.isPresent()) {
            Binding binding = previous.get();
            BigInteger version = binding.version().add(BigInteger.ONE);
            if (version.compareTo(MAX_ID) > 0) {
                throw new DatabaseException("Meter binding version overflow");
            }
            database.execute("""
                    INSERT INTO fact_gauge_bindings (gauge_id, realty_id, created_at, sign, version)
                    SELECT {old_gauge:UInt64}, {realty:UInt64}, {old_created:DateTime}, -1, {old_version:UInt64}
                    UNION ALL
                    SELECT {gauge:UInt64}, {realty:UInt64}, {installed:DateTime}, 1, {version:UInt64}
                    """, Map.of("old_gauge", binding.gaugeId(), "realty", realty,
                    "old_created", binding.createdAt(), "old_version", binding.version(),
                    "gauge", gauge, "installed", installed, "version", version));
        } else {
            database.execute("""
                    INSERT INTO fact_gauge_bindings (gauge_id, realty_id, created_at, sign, version)
                    SELECT {gauge:UInt64}, {realty:UInt64}, {installed:DateTime}, 1, 1
                    """, Map.of("gauge", gauge, "realty", realty, "installed", installed));
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Binding(BigInteger gaugeId, String createdAt, BigInteger version) {}
}
