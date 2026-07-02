package com.nbcb.mcp.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 通用数值计算器 MCP 工具
 * <p>
 * 支持基本的四则运算（加减乘除）和括号优先级。
 * 使用安全的表达式解析，避免直接调用 JavaScript 引擎等不安全方式。
 * <p>
 * 工具调用失败时抛出 {@link IllegalArgumentException}，
 * Spring AI 框架会自动捕获并转换为工具错误响应传递给 Agent。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class CalculatorTool {

    /**
     * 执行数值表达式计算
     * <p>
     * MCP 工具名称：calculate（默认取自方法名）
     *
     * @param expression 数学表达式，如 "2+3*4"、"(1+2)*3"、"100/4"
     * @return 计算结果字符串，包含原始表达式和结果
     * @throws IllegalArgumentException 当表达式格式非法或包含不支持的运算时
     */
    @Tool(description = "执行数学表达式计算，支持加减乘除和括号，例如 2+3*4、(1+2)*3")
    public String calculate(
            @ToolParam(description = "数学表达式，例如 2+3*4 或 (1+2)*3") String expression) {

        log.info("MCP 工具[calculate]被调用，参数 expression={}", expression);

        // 入参校验
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空，请输入如 2+3*4 的计算表达式");
        }

        // 安全校验：只允许数字、运算符、括号、空格、小数点
        if (!expression.matches("^[\\d+\\-*/().\\s]+$")) {
            throw new IllegalArgumentException(
                    "表达式包含非法字符，只允许数字、+、-、*、/、(、)、.和空格");
        }

        try {
            double result = evaluate(expression);
            String resultStr = String.format(
                    "{\"expression\":\"%s\",\"result\":%s}", expression, result);
            log.info("MCP 工具[calculate]计算结果：{}", resultStr);
            return resultStr;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("计算错误：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "表达式解析失败：" + expression + "，请检查格式是否正确", e);
        }
    }

    /**
     * 简化的表达式求值器
     * <p>
     * 使用递归下降法解析加减乘除和括号。
     * 不依赖 ScriptEngine，避免潜在安全风险。
     */
    private double evaluate(String expression) {
        expression = expression.replaceAll("\\s+", ""); // 去除空格
        return new ExpressionParser(expression).parse();
    }

    /**
     * 递归下降表达式解析器（内部类）
     */
    private static class ExpressionParser {
        private final String expr;
        private int pos;

        ExpressionParser(String expr) {
            this.expr = expr;
            this.pos = 0;
        }

        double parse() {
            double result = parseTerm();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '+') {
                    pos++;
                    result += parseTerm();
                } else if (op == '-') {
                    pos++;
                    result -= parseTerm();
                } else {
                    break;
                }
            }
            return result;
        }

        double parseTerm() {
            double result = parseFactor();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '*') {
                    pos++;
                    result *= parseFactor();
                } else if (op == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数不能为零");
                    }
                    result /= divisor;
                } else {
                    break;
                }
            }
            return result;
        }

        double parseFactor() {
            if (pos >= expr.length()) {
                throw new IllegalArgumentException("表达式不完整");
            }

            char ch = expr.charAt(pos);
            if (ch == '(') {
                pos++; // 跳过 '('
                double result = parse();
                if (pos < expr.length() && expr.charAt(pos) == ')') {
                    pos++; // 跳过 ')'
                } else {
                    throw new IllegalArgumentException("缺少右括号')'");
                }
                return result;
            } else if (ch == '-') {
                // 负号处理
                pos++;
                return -parseFactor();
            } else if (Character.isDigit(ch) || ch == '.') {
                return parseNumber();
            } else {
                throw new IllegalArgumentException("非法字符 '" + ch + "'");
            }
        }

        double parseNumber() {
            int start = pos;
            while (pos < expr.length() &&
                    (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                pos++;
            }
            return Double.parseDouble(expr.substring(start, pos));
        }
    }
}
