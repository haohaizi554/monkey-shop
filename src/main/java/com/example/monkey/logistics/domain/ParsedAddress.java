package com.example.monkey.logistics.domain;

public record ParsedAddress(String province, String city, String district, String detail) {

    public String snapshot() {
        return join(province, city, district, detail);
    }

    public String summary() {
        String snapshot = snapshot();
        return snapshot.length() <= 255 ? snapshot : snapshot.substring(0, 255);
    }

    private static String join(String province, String city, String district, String detail) {
        StringBuilder builder = new StringBuilder();
        append(builder, province);
        append(builder, city);
        append(builder, district);
        append(builder, detail);
        return builder.toString().trim();
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(value.trim());
        }
    }
}
