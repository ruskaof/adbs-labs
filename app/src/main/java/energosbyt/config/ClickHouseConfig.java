package energosbyt.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "clickhouse")
public record ClickHouseConfig(
        @NotBlank String host,
        @Min(1) @Max(65535) int httpPort,
        @NotBlank String database,
        @NotBlank String user,
        String password) {}
