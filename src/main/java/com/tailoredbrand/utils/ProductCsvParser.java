package com.tailoredbrand.utils;

import com.tailoredbrand.model.ProductCsvRecord;

public final class ProductCsvParser {
    public static final String HEADER_PREFIX = "ItemCode|ParentProductCode|";
    private static final int COLUMN_COUNT = 74;

    private ProductCsvParser() {
    }

    public static boolean isHeaderLine(String line) {
        if (line == null) {
            return false;
        }
        String normalized = normalizeLine(line);
        return normalized.startsWith(HEADER_PREFIX);
    }

    public static ProductCsvRecord parseLine(String line) {
        String normalized = normalizeLine(line);
        String[] tokens = normalized.split("\\|", -1);
        if (tokens.length != COLUMN_COUNT) {
            throw new IllegalArgumentException(
                    "CSV column count mismatch. Expected " + COLUMN_COUNT + " but got " + tokens.length);
        }
        return new ProductCsvRecord(
                value(tokens, 0),
                value(tokens, 1),
                value(tokens, 2),
                value(tokens, 3),
                value(tokens, 4),
                value(tokens, 5),
                value(tokens, 6),
                value(tokens, 7),
                value(tokens, 8),
                value(tokens, 9),
                value(tokens, 10),
                value(tokens, 11),
                value(tokens, 12),
                value(tokens, 13),
                value(tokens, 14),
                value(tokens, 15),
                value(tokens, 16),
                value(tokens, 17),
                value(tokens, 18),
                value(tokens, 19),
                value(tokens, 20),
                value(tokens, 21),
                value(tokens, 22),
                value(tokens, 23),
                value(tokens, 24),
                value(tokens, 25),
                value(tokens, 26),
                value(tokens, 27),
                value(tokens, 28),
                value(tokens, 29),
                value(tokens, 30),
                value(tokens, 31),
                value(tokens, 32),
                value(tokens, 33),
                value(tokens, 34),
                value(tokens, 35),
                value(tokens, 36),
                value(tokens, 37),
                value(tokens, 38),
                value(tokens, 39),
                value(tokens, 40),
                value(tokens, 41),
                value(tokens, 42),
                value(tokens, 43),
                value(tokens, 44),
                value(tokens, 45),
                value(tokens, 46),
                value(tokens, 47),
                value(tokens, 48),
                value(tokens, 49),
                value(tokens, 50),
                value(tokens, 51),
                value(tokens, 52),
                value(tokens, 53),
                value(tokens, 54),
                value(tokens, 55),
                value(tokens, 56),
                value(tokens, 57),
                value(tokens, 58),
                value(tokens, 59),
                value(tokens, 60),
                value(tokens, 61),
                value(tokens, 62),
                value(tokens, 63),
                value(tokens, 64),
                value(tokens, 65),
                value(tokens, 66),
                value(tokens, 67),
                value(tokens, 68),
                value(tokens, 69),
                value(tokens, 70),
                value(tokens, 71),
                value(tokens, 72),
                value(tokens, 73)
        );
    }

    private static String normalizeLine(String line) {
        if (line != null && line.startsWith("\uFEFF")) {
            return line.substring(1);
        }
        return line;
    }

    private static String value(String[] tokens, int index) {
        String token = tokens[index];
        return token == null || token.isEmpty() ? null : token;
    }
}
