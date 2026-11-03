package com.jezrel.syncnotes.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Who a request came from, for rate limiting. Behind Fly.io the connection's address is the proxy's,
 * so Fly-Client-IP is used when present. X-Forwarded-For is ignored: its leftmost entries are
 * whatever the client sent.
 */
public final class ClientIp {

    static final String HEADER = "Fly-Client-IP";
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        return group(header == null || header.isBlank() ? request.getRemoteAddr() : header.strip());
    }

    /**
     * One home or phone is typically given a whole IPv6 /64, so counting single addresses would let
     * it rotate past any limit. Everything else is returned as it is.
     */
    private static String group(String ip) {
        // The check matters: a value with a colon and only these characters can't trigger a DNS lookup.
        if (!ip.contains(":") || !IP_LITERAL.matcher(ip).matches()) {
            return ip;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] bytes = address.getAddress();
            if (bytes.length == 16) {
                return HexFormat.of().formatHex(bytes, 0, 8) + "/64";
            }
            return address.getHostAddress();          // an IPv4-mapped address is that IPv4 client
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
