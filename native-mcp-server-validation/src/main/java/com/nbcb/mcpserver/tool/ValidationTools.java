package com.nbcb.mcpserver.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 网关内部执行的工具业务服务。
 */
@Slf4j
@Service
public class ValidationTools {

    private static final Map<String, WeatherResult> WEATHER_DATA = Map.of(
            "北京", new WeatherResult("北京", "26℃", "晴转多云", "适合出行，注意防晒"),
            "上海", new WeatherResult("上海", "29℃", "小雨", "出门请带伞"),
            "深圳", new WeatherResult("深圳", "32℃", "多云", "注意防暑降温"));

    /**
     * 安全计算四则运算表达式。
     */
    public CalculationResult calculate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        if (!expression.matches("^[\\d+\\-*/().\\s]+$")) {
            throw new IllegalArgumentException("表达式包含非法字符");
        }

        double result = new ExpressionParser(
                expression.replaceAll("\\s+", "")).parseFully();
        log.info("AI 网关 calculate 调用完成：{} = {}", expression, result);
        return new CalculationResult(expression, result);
    }

    /**
     * 查询模拟天气数据。
     */
    public WeatherResult getWeatherByCity(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }
        WeatherResult result = WEATHER_DATA.getOrDefault(
                city, new WeatherResult(city, "22℃", "晴", "天气不错，适合户外活动"));
        log.info("AI 网关 getWeatherByCity 调用完成：{}", city);
        return result;
    }

    /**
     * 计算结果。
     */
    public record CalculationResult(String expression, double result) {
    }

    /**
     * 天气结果。
     */
    public record WeatherResult(
            String city,
            String temperature,
            String condition,
            String advice) {
    }

    /**
     * 递归下降表达式解析器，不执行任意脚本。
     */
    private static final class ExpressionParser {

        private final String expression;
        private int position;

        private ExpressionParser(String expression) {
            this.expression = expression;
        }

        private double parseFully() {
            double value = parseExpression();
            if (position != expression.length()) {
                throw new IllegalArgumentException(
                        "无法解析位置 " + position + " 附近的表达式");
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (position < expression.length()) {
                char operator = expression.charAt(position);
                if (operator != '+' && operator != '-') {
                    break;
                }
                position++;
                double right = parseTerm();
                value = operator == '+' ? value + right : value - right;
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (position < expression.length()) {
                char operator = expression.charAt(position);
                if (operator != '*' && operator != '/') {
                    break;
                }
                position++;
                double right = parseFactor();
                if (operator == '/' && right == 0) {
                    throw new IllegalArgumentException("除数不能为零");
                }
                value = operator == '*' ? value * right : value / right;
            }
            return value;
        }

        private double parseFactor() {
            if (position >= expression.length()) {
                throw new IllegalArgumentException("表达式不完整");
            }
            char current = expression.charAt(position);
            if (current == '-') {
                position++;
                return -parseFactor();
            }
            if (current == '(') {
                position++;
                double value = parseExpression();
                if (position >= expression.length()
                        || expression.charAt(position) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                position++;
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            int start = position;
            while (position < expression.length()) {
                char current = expression.charAt(position);
                if (!Character.isDigit(current) && current != '.') {
                    break;
                }
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("期望数字，位置：" + position);
            }
            try {
                return Double.parseDouble(expression.substring(start, position));
            }
            catch (NumberFormatException ex) {
                throw new IllegalArgumentException("数字格式错误", ex);
            }
        }
    }
}
