package com.example.monkey.shared.web;

public final class ApiPaths {

    private static final String API_PREFIX = "/api/";
    private static final String API_V1_PREFIX = "/api/v1/";

    private ApiPaths() {}

    public static boolean isApiRequest(String path) {
        return path != null && path.startsWith(API_PREFIX);
    }

    public static String canonicalize(String path) {
        if (path == null || !path.startsWith(API_V1_PREFIX)) {
            return path;
        }
        String suffix = path.substring(API_V1_PREFIX.length());
        if (matchesResource(suffix, "addresses")) {
            return "/api/address" + suffix.substring("addresses".length());
        }
        if (matchesResource(suffix, "users")) {
            return "/api/user" + suffix.substring("users".length());
        }
        if (matchesResource(suffix, "uploads")) {
            return "/api/upload" + suffix.substring("uploads".length());
        }
        return "/api/" + suffix;
    }

    private static boolean matchesResource(String suffix, String resource) {
        return suffix.equals(resource) || suffix.startsWith(resource + "/");
    }
}
