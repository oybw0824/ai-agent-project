package com.nbcb.nacosmcpagent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherToolTest {

    private final WeatherTool weatherTool =
            new WeatherTool("weather-provider-a");

    @Test
    void shouldReturnWeatherWithProvider() {
        WeatherTool.WeatherResult result =
                weatherTool.getWeatherByCity("北京");

        assertThat(result.provider()).isEqualTo("weather-provider-a");
        assertThat(result.city()).isEqualTo("北京");
        assertThat(result.temperature()).isEqualTo("26℃");
    }

    @Test
    void shouldRejectBlankCity() {
        assertThatThrownBy(() -> weatherTool.getWeatherByCity(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
