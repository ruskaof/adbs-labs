package energosbyt.repository;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import energosbyt.exception.DatabaseException;

@Component
public class ClickHouseQueries {
    private final Client client;
    private final JsonMapper json;

    public ClickHouseQueries(Client client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public <T> List<T> rows(String sql, Map<String, ?> parameters, Class<T> type) {
        String result = query(sql, parameters, ClickHouseFormat.JSONEachRow);
        return result.lines().map(line -> json.readValue(line, type)).toList();
    }

    public String execute(String sql) {
        return execute(sql, Map.of());
    }

    public String execute(String sql, Map<String, ?> parameters) {
        return query(sql, parameters, ClickHouseFormat.TabSeparated);
    }

    private String query(String sql, Map<String, ?> parameters, ClickHouseFormat format) {
        var settings = new QuerySettings().setFormat(format);
        try (var response = client.query(sql, new HashMap<>(parameters), settings).get()) {
            return new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("ClickHouse request interrupted", error);
        } catch (ExecutionException error) {
            throw new DatabaseException("ClickHouse request failed", error.getCause());
        } catch (Exception error) {
            throw new DatabaseException("ClickHouse request failed", error);
        }
    }
}
