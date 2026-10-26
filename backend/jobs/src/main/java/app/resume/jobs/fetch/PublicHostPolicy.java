package app.resume.jobs.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * SSRF protection (contract: createSource and ingestPosting). Only hosts whose every address is public are allowed:
 * localhost, loopback, link-local (including 169.254.169.254), private (RFC 1918, unique local), carrier-grade NAT and
 * other non-routable ranges are refused. {@link #checkLiteral} is the cheap check done when a URL is accepted (no DNS);
 * {@link #check} resolves the name and tests every address, and runs before every fetch and every redirect hop.
 *
 * <p>Known limit: the name is resolved here and again by the HTTP client when it connects, so a DNS server that answers
 * differently the second time (rebinding) is not stopped by this class alone; the renderer-style network isolation of
 * the deployment is the second layer.
 */
public class PublicHostPolicy implements HostPolicy {
    /** Looks a name up; replaceable in tests. */
    private final Function<String, InetAddress[]> resolver;
    /** Dotted numbers (decimal, 0x-hex or leading-zero octal parts) are IPv4 literals in some notation, never names. */
    private static final Pattern IPV4_LIKE = Pattern.compile("^(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\.(?:0[xX][0-9a-fA-F]+|[0-9]+))*$");

    /** TEST/E2E ONLY: when true every host is allowed (loopback, private, link-local). Production leaves this false. */
    private final boolean allowPrivateHosts;

    public PublicHostPolicy() {
        this(false);
    }

    /**
     * @param allowPrivateHosts test/e2e switch (`app.jobs.fetch.allow-private-hosts`, default false): lets the pipeline
     *     crawl a fixture site on localhost. Never enable it in a deployment: it turns the SSRF protection off.
     */
    public PublicHostPolicy(boolean allowPrivateHosts) {
        this(allowPrivateHosts, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new FetchException("host does not resolve: " + host, e);
            }
        });
    }

    public PublicHostPolicy(Function<String, InetAddress[]> resolver) {
        this(false, resolver);
    }

    PublicHostPolicy(boolean allowPrivateHosts, Function<String, InetAddress[]> resolver) {
        this.allowPrivateHosts = allowPrivateHosts;
        this.resolver = resolver;
    }

    /** Cheap, DNS-free check for a URL as typed: scheme, a host, and no refused literal address or localhost name. */
    public void checkLiteral(String url) {
        String host = hostOf(url); // the scheme and shape checks always apply
        if (allowPrivateHosts) return;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal") || lower.endsWith(".local")) {
            throw new UrlNotAllowedException("host is not public: " + host);
        }
        if (looksLikeLiteral(host)) {
            for (InetAddress a : literal(host)) refuseIfNotPublic(a, host);
        }
    }

    @Override
    public void check(String host) {
        if (allowPrivateHosts) return;
        checkLiteral("http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + "/");
        if (looksLikeLiteral(stripBrackets(host))) return; // already checked, nothing to resolve
        InetAddress[] all = resolver.apply(host);
        if (all == null || all.length == 0) throw new FetchException("host does not resolve: " + host);
        for (InetAddress a : all) refuseIfNotPublic(a, host);
    }

    private static String hostOf(String url) {
        URI u;
        try {
            u = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new UrlNotAllowedException("invalid url");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || u.getHost() == null) {
            throw new UrlNotAllowedException("not an absolute http(s) url");
        }
        return stripBrackets(u.getHost());
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean looksLikeLiteral(String host) {
        return host.contains(":") || IPV4_LIKE.matcher(host).matches();
    }

    /** Parses IPv4 in any common notation (dotted, decimal, hex, octal, short forms) or IPv6, without DNS. */
    private static InetAddress[] literal(String host) {
        try {
            if (host.contains(":")) return new InetAddress[] {InetAddress.getByName(host)};
            String[] parts = host.split("\\.", -1);
            if (parts.length < 1 || parts.length > 4) throw new UrlNotAllowedException("host is not public: " + host);
            long[] n = new long[parts.length];
            for (int i = 0; i < parts.length; i++) n[i] = parseNumber(parts[i], host);
            long value = 0;
            for (int i = 0; i < n.length - 1; i++) {
                if (n[i] > 255) throw new UrlNotAllowedException("host is not public: " + host);
                value |= n[i] << (8 * (3 - i));
            }
            long last = n[n.length - 1];
            long max = (1L << (8 * (5 - n.length))) - 1;
            if (last > max) throw new UrlNotAllowedException("host is not public: " + host);
            value |= last;
            return new InetAddress[] {InetAddress.getByAddress(new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value})};
        } catch (UnknownHostException e) {
            throw new UrlNotAllowedException("host is not public: " + host);
        }
    }

    private static long parseNumber(String s, String host) {
        try {
            if (s.isEmpty()) throw new NumberFormatException();
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
            if (s.length() > 1 && s.startsWith("0")) return Long.parseLong(s.substring(1), 8);
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new UrlNotAllowedException("host is not public: " + host); // odd notation: refuse rather than guess
        }
    }

    static void refuseIfNotPublic(InetAddress a, String host) {
        if (!isPublic(a)) throw new UrlNotAllowedException("host is not public: " + host);
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            int b2 = b[2] & 0xff;
            if (b0 == 0) return false;                                  // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;      // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 192 && b1 == 0 && b2 == 0) return false;         // 192.0.0.0/24 IETF protocol assignments
            if (b0 == 192 && b1 == 0 && b2 == 2) return false;         // 192.0.2.0/24 documentation
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;     // 198.18.0.0/15 benchmarking
            if (b0 == 198 && b1 == 51 && b2 == 100) return false;      // 198.51.100.0/24 documentation
            if (b0 == 203 && b1 == 0 && b2 == 113) return false;       // 203.0.113.0/24 documentation
            if (b0 >= 240) return false;                                // 240.0.0.0/4 reserved, broadcast
            return true;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return false;                      // fc00::/7 unique local
            if (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) return false; // 2001:db8::/32
            if (b0 == 0x20 && b1 == 0x02) return false;                 // 2002::/16 6to4 (embeds an IPv4 address)
            if (b0 == 0x01 && b1 == 0x00 && allZero(b, 2, 8)) return false; // 100::/64 discard
            if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) return false; // 64:ff9b::/96 NAT64
            if (b0 == 0x00 && allZero(b, 1, 10)) return false;          // ::/80 and IPv4-compatible forms
            return true;
        }
        return false;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }
}
