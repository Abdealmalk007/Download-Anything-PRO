package com.downloadanything.pro;

final class SourceBinding {
    private SourceBinding() {}
    static boolean isCurrent(long request, long current, String expectedUrl, String inputUrl) {
        return request==current && expectedUrl!=null && !expectedUrl.isEmpty() && expectedUrl.equals(inputUrl);
    }
    static boolean matches(String expectedId,String resolvedId) {
        return expectedId!=null && !expectedId.isEmpty() && expectedId.equals(resolvedId);
    }
}
