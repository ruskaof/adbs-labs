package energosbyt.config;

import java.time.temporal.ChronoUnit;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.enums.Protocol;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClickHouseConfig.class)
public class ClickHouseConfiguration {
    @Bean(destroyMethod = "close")
    public Client clickHouseClient(ClickHouseConfig config) {
        return new Client.Builder()
                .addEndpoint(Protocol.HTTP, config.host(), config.httpPort(), false)
                .setDefaultDatabase(config.database())
                .setUsername(config.user())
                .setPassword(config.password())
                .setConnectTimeout(2, ChronoUnit.SECONDS)
                .setSocketTimeout(15, ChronoUnit.SECONDS)
                .setExecutionTimeout(15, ChronoUnit.SECONDS)
                .setMaxRetries(0)
                .serverSetting("session_timezone", "UTC")
                .serverSetting("wait_end_of_query", "1")
                .serverSetting("output_format_json_quote_64bit_integers", "0")
                .build();
    }
}
