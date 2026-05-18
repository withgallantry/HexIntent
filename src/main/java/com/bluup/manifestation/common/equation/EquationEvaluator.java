package com.bluup.manifestation.common.equation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EquationEvaluator {
    public interface CompiledExpression {
        double eval(double t, double u);
    }

    private EquationEvaluator() {
    }

    public static CompiledExpression compile(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("empty_expression");
        }
        Parser parser = new Parser(expression);
        Node root = parser.parse();
        return (t, u) -> {
            double v = root.eval(t, u);
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("non_finite_result");
            }
            return v;
        };
    }

    private interface Node {
        double eval(double t, double u);
    }

    private record NumberNode(double value) implements Node {
        @Override
        public double eval(double t, double u) {
            return value;
        }
    }

    private record VarNode(char symbol) implements Node {
        @Override
        public double eval(double t, double u) {
            return symbol == 't' ? t : u;
        }
    }

    private record UnaryNode(char op, Node child) implements Node {
        @Override
        public double eval(double t, double u) {
            double v = child.eval(t, u);
            return op == '-' ? -v : v;
        }
    }

    private record BinaryNode(char op, Node left, Node right) implements Node {
        @Override
        public double eval(double t, double u) {
            double a = left.eval(t, u);
            double b = right.eval(t, u);
            return switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> a / b;
                case '^' -> Math.pow(a, b);
                default -> throw new IllegalStateException("Unexpected op: " + op);
            };
        }
    }

    private record FuncNode(String name, List<Node> args) implements Node {
        @Override
        public double eval(double t, double u) {
            return switch (name) {
                case "sin" -> Math.sin(args.get(0).eval(t, u));
                case "cos" -> Math.cos(args.get(0).eval(t, u));
                case "tan" -> Math.tan(args.get(0).eval(t, u));
                case "asin" -> Math.asin(args.get(0).eval(t, u));
                case "acos" -> Math.acos(args.get(0).eval(t, u));
                case "atan" -> Math.atan(args.get(0).eval(t, u));
                case "sqrt" -> Math.sqrt(args.get(0).eval(t, u));
                case "abs" -> Math.abs(args.get(0).eval(t, u));
                case "exp" -> Math.exp(args.get(0).eval(t, u));
                case "log" -> Math.log(args.get(0).eval(t, u));
                case "floor" -> Math.floor(args.get(0).eval(t, u));
                case "ceil" -> Math.ceil(args.get(0).eval(t, u));
                case "round" -> Math.rint(args.get(0).eval(t, u));
                case "min" -> Math.min(args.get(0).eval(t, u), args.get(1).eval(t, u));
                case "max" -> Math.max(args.get(0).eval(t, u), args.get(1).eval(t, u));
                case "pow" -> Math.pow(args.get(0).eval(t, u), args.get(1).eval(t, u));
                default -> throw new IllegalArgumentException("unknown_function");
            };
        }
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        private Parser(String expression) {
            this.tokens = tokenize(expression);
        }

        private Node parse() {
            Node out = parseExpression();
            expect(TokenType.EOF);
            return out;
        }

        private Node parseExpression() {
            Node left = parseTerm();
            while (matchSymbol('+') || matchSymbol('-')) {
                char op = previous().symbol;
                Node right = parseTerm();
                left = new BinaryNode(op, left, right);
            }
            return left;
        }

        private Node parseTerm() {
            Node left = parsePower();
            while (matchSymbol('*') || matchSymbol('/')) {
                char op = previous().symbol;
                Node right = parsePower();
                left = new BinaryNode(op, left, right);
            }
            return left;
        }

        private Node parsePower() {
            Node left = parseUnary();
            if (matchSymbol('^')) {
                Node right = parsePower();
                return new BinaryNode('^', left, right);
            }
            return left;
        }

        private Node parseUnary() {
            if (matchSymbol('+') || matchSymbol('-')) {
                char op = previous().symbol;
                return new UnaryNode(op, parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            if (match(TokenType.NUMBER)) {
                return new NumberNode(previous().number);
            }
            if (match(TokenType.IDENT)) {
                String name = previous().text;
                if ("t".equals(name) || "u".equals(name)) {
                    return new VarNode(name.charAt(0));
                }
                if ("pi".equals(name)) {
                    return new NumberNode(Math.PI);
                }
                if ("e".equals(name)) {
                    return new NumberNode(Math.E);
                }

                expectSymbol('(');
                ArrayList<Node> args = new ArrayList<>();
                if (!checkSymbol(')')) {
                    do {
                        args.add(parseExpression());
                    } while (matchSymbol(','));
                }
                expectSymbol(')');
                validateArity(name, args.size());
                return new FuncNode(name, args);
            }
            if (matchSymbol('(')) {
                Node inner = parseExpression();
                expectSymbol(')');
                return inner;
            }
            throw error("unexpected_token");
        }

        private void validateArity(String name, int size) {
            int expected = switch (name) {
                case "min", "max", "pow" -> 2;
                default -> 1;
            };
            if (size != expected) {
                throw error("invalid_arity");
            }
        }

        private boolean match(TokenType type) {
            if (!check(type)) {
                return false;
            }
            index++;
            return true;
        }

        private boolean check(TokenType type) {
            return peek().type == type;
        }

        private boolean matchSymbol(char symbol) {
            if (!checkSymbol(symbol)) {
                return false;
            }
            index++;
            return true;
        }

        private boolean checkSymbol(char symbol) {
            Token token = peek();
            return token.type == TokenType.SYMBOL && token.symbol == symbol;
        }

        private void expect(TokenType type) {
            if (!match(type)) {
                throw error("expected_" + type.name().toLowerCase(Locale.ROOT));
            }
        }

        private void expectSymbol(char symbol) {
            if (!matchSymbol(symbol)) {
                throw error("expected_symbol_" + symbol);
            }
        }

        private Token previous() {
            return tokens.get(index - 1);
        }

        private Token peek() {
            return tokens.get(index);
        }

        private IllegalArgumentException error(String code) {
            return new IllegalArgumentException(code + "_at_" + index);
        }
    }

    private enum TokenType {
        NUMBER,
        IDENT,
        SYMBOL,
        EOF
    }

    private record Token(TokenType type, String text, double number, char symbol) {
        private static Token number(double value) {
            return new Token(TokenType.NUMBER, "", value, '\0');
        }

        private static Token ident(String text) {
            return new Token(TokenType.IDENT, text, 0.0, '\0');
        }

        private static Token symbol(char symbol) {
            return new Token(TokenType.SYMBOL, "", 0.0, symbol);
        }

        private static Token eof() {
            return new Token(TokenType.EOF, "", 0.0, '\0');
        }
    }

    private static List<Token> tokenize(String expression) {
        ArrayList<Token> out = new ArrayList<>();
        int i = 0;
        while (i < expression.length()) {
            char ch = expression.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = i;
                i++;
                while (i < expression.length()) {
                    char c = expression.charAt(i);
                    if (Character.isDigit(c) || c == '.') {
                        i++;
                        continue;
                    }
                    if ((c == 'e' || c == 'E') && i + 1 < expression.length()) {
                        char n = expression.charAt(i + 1);
                        if (Character.isDigit(n) || n == '+' || n == '-') {
                            i += 2;
                            while (i < expression.length() && Character.isDigit(expression.charAt(i))) {
                                i++;
                            }
                            continue;
                        }
                    }
                    break;
                }
                String num = expression.substring(start, i);
                double value;
                try {
                    value = Double.parseDouble(num);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("bad_number");
                }
                out.add(Token.number(value));
                continue;
            }
            if (Character.isLetter(ch) || ch == '_') {
                int start = i;
                i++;
                while (i < expression.length()) {
                    char c = expression.charAt(i);
                    if (Character.isLetterOrDigit(c) || c == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(Token.ident(expression.substring(start, i).toLowerCase(Locale.ROOT)));
                continue;
            }
            if ("+-*/^(),".indexOf(ch) >= 0) {
                out.add(Token.symbol(ch));
                i++;
                continue;
            }
            throw new IllegalArgumentException("bad_char_" + ch);
        }
        out.add(Token.eof());
        return out;
    }
}
