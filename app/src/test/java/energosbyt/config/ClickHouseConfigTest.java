package energosbyt.config;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("APP_PORT=9091", "CLICKHOUSE_HOST=localhost", "CLICKHOUSE_HTTP_PORT=8123",
                    "CLICKHOUSE_DATABASE=energosbyt", "CLICKHOUSE_USER=energosbyt");

    @Test
    void bindsEnvironmentPlaceholdersFromApplicationYaml() {
        context.withPropertyValues("CLICKHOUSE_PASSWORD=a'b\"c\\d $literal #value").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(ClickHouseConfig.class)).isEqualTo(
                    new ClickHouseConfig("localhost", 8123, "energosbyt", "energosbyt", "a'b\"c\\d $literal #value"));
            assertThat(application.getEnvironment().getProperty("server.port", Integer.class)).isEqualTo(9091);
        });
    }

    @Test
    void allowsEmptyPassword() {
        context.withPropertyValues("CLICKHOUSE_PASSWORD=").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(ClickHouseConfig.class).password()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLICKHOUSE_HOST", "CLICKHOUSE_HTTP_PORT", "CLICKHOUSE_DATABASE", "CLICKHOUSE_USER"})
    void rejectsMissingConnectionSettings(String name) {
        String[] properties = Stream.of("CLICKHOUSE_HOST=localhost", "CLICKHOUSE_HTTP_PORT=8123",
                        "CLICKHOUSE_DATABASE=energosbyt", "CLICKHOUSE_USER=energosbyt")
                .filter(value -> !value.startsWith(name + "="))
                .toArray(String[]::new);
        new ApplicationContextRunner()
                .withInitializer(application -> application.getEnvironment().getPropertySources().remove("systemEnvironment"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(properties)
                .run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "invalid", "999999999999999999999"})
    void rejectsInvalidClickHousePort(String port) {
        context.withPropertyValues("CLICKHOUSE_HTTP_PORT=" + port).run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLICKHOUSE_HOST", "CLICKHOUSE_DATABASE", "CLICKHOUSE_USER"})
    void rejectsBlankConnectionSettings(String name) {
        context.withPropertyValues(name + "= ").run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure()).hasStackTraceContaining("must not be blank");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClickHouseConfig.class)
    static class TestConfiguration {}
}
