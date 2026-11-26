package com.artex.mobile;

/**
 * 把网络异常翻译成用户能看懂的话。
 *
 * 直接把 java.net.UnknownHostException 甩到面板上没意义——
 * 手机上没有代理时访问 GitHub 就是这个异常，用户需要知道的是
 * "可能需要开代理"，不是类名。
 */
final class NetErr {

    private NetErr() {}

    static String describe(Throwable t) {
        if (t == null) return "Unknown error";
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        String low = m.toLowerCase();
        if (low.contains("unable to resolve host") || low.contains("unknownhost")) {
            return "Unable to resolve GitHub hostname (a proxy may be required)";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "Connection timed out (a proxy may be required)";
        }
        if (low.contains("econnrefused") || low.contains("connection refused")) {
            return "Connection refused";
        }
        if (low.contains("network is unreachable") || low.contains("unreachable")) {
            return "Network unreachable";
        }
        if (low.contains("ssl") || low.contains("certificate")) {
            return "TLS handshake failed: " + m;
        }
        return m;
    }
}
