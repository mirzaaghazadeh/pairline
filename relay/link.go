package main

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// The relay's link (PROTOCOL.md, "Your own server (relay, D-106)"):
//
//	pairline-relay://<host>:<port>?v=1&fp=<certificate SHA-256>&k=<access key>
//
// The phones check it strictly; this side only builds it from what the user said the server's address is.
func buildLink(host string, port int, fingerprint [32]byte, key [accessKeySize]byte) string {
	shown := host
	if strings.Contains(host, ":") {
		shown = "[" + host + "]"
	}
	return fmt.Sprintf("pairline-relay://%s:%d?v=1&fp=%s&k=%s", shown, port,
		b64.EncodeToString(fingerprint[:]), b64.EncodeToString(key[:]))
}

// publicEndpoint reads PAIRLINE_RELAY_PUBLIC: a DNS name, an IPv4 address or an IPv6 address (bracketed when a port
// follows), with an optional port; without one the port the relay listens on is used.
func publicEndpoint(value string, listenPort int) (string, int, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", 0, errors.New("empty")
	}
	host, port := value, listenPort
	if strings.HasPrefix(value, "[") {
		end := strings.Index(value, "]")
		if end < 0 {
			return "", 0, errors.New("an IPv6 address opened with [ but never closed")
		}
		host = value[1:end]
		rest := value[end+1:]
		if rest != "" {
			if !strings.HasPrefix(rest, ":") {
				return "", 0, fmt.Errorf("unexpected %q after the address", rest)
			}
			p, err := parsePort(rest[1:])
			if err != nil {
				return "", 0, err
			}
			port = p
		}
	} else if strings.Count(value, ":") == 1 {
		h, p, _ := strings.Cut(value, ":")
		n, err := parsePort(p)
		if err != nil {
			return "", 0, err
		}
		host, port = h, n
	}
	if port == 0 {
		return "", 0, errors.New("no port: add one (for example :443)")
	}
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if addr, err := netip.ParseAddr(host); err == nil {
		if addr.Zone() != "" {
			return "", 0, errors.New("an address with a zone (%) only works inside one network")
		}
		return addr.Unmap().String(), port, nil
	}
	if !isDNSName(host) {
		return "", 0, fmt.Errorf("%q is neither an IP address nor a DNS name", host)
	}
	return host, port, nil
}

func parsePort(text string) (int, error) {
	n, err := strconv.Atoi(text)
	if err != nil || n < 1 || n > 65535 || (len(text) > 1 && text[0] == '0') {
		return 0, fmt.Errorf("%q isn't a port from 1 to 65535", text)
	}
	return n, nil
}

// isDNSName: letters, digits and hyphens per label, at least two labels, the last one not all digits.
func isDNSName(host string) bool {
	if len(host) == 0 || len(host) > 253 {
		return false
	}
	labels := strings.Split(host, ".")
	if len(labels) < 2 {
		return false
	}
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return false
			}
		}
	}
	last := labels[len(labels)-1]
	return strings.Trim(last, "0123456789") != ""
}

// addressWarning says why the phones would refuse an address, or "" when they'd take it.
func addressWarning(host string) string {
	addr, err := netip.ParseAddr(host)
	if err != nil {
		switch last := host[strings.LastIndex(host, ".")+1:]; last {
		case "local", "localhost", "lan", "home", "internal":
			return "names ending in ." + last + " work only inside one network; the phones refuse them"
		}
		return ""
	}
	if !isPublic(addr) {
		return "this address isn't reachable from the internet (private, carrier-grade NAT, loopback or reserved); " +
			"the phones refuse it outside debug builds"
	}
	return ""
}

// isPublic: the addresses the phones accept for a relay (the same rules as the direct connection's, D-092).
func isPublic(addr netip.Addr) bool {
	addr = addr.Unmap()
	if addr.Is4() {
		b := addr.As4()
		switch {
		case b[0] == 0, b[0] == 10, b[0] == 127, b[0] >= 224:
			return false
		case b[0] == 100 && b[1]&0xc0 == 64, b[0] == 169 && b[1] == 254, b[0] == 172 && b[1]&0xf0 == 16,
			b[0] == 192 && b[1] == 168, b[0] == 198 && b[1]&0xfe == 18:
			return false
		case b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2), b[0] == 192 && b[1] == 88 && b[2] == 99,
			b[0] == 198 && b[1] == 51 && b[2] == 100, b[0] == 203 && b[1] == 0 && b[2] == 113:
			return false
		}
		return true
	}
	b := addr.As16()
	if b[0]&0xe0 != 0x20 {
		return false // outside 2000::/3: unique-local, link-local, loopback, multicast, mapped, NAT64
	}
	switch {
	case b[0] == 0x20 && b[1] == 0x01:
		return b[2] >= 0x02 && !(b[2] == 0x0d && b[3] == 0xb8)
	case b[0] == 0x20 && b[1] == 0x02:
		return false
	case b[0] == 0x3f && b[1] == 0xff:
		return b[2]&0xf0 != 0
	}
	return true
}

// detectPublicAddress looks at this machine's own interfaces (it never asks an outside service) for exactly one
// public address, as on a server with a public IPv4 on its network card. Inside Docker's own network there is none.
func detectPublicAddress() (string, bool) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "", false
	}
	var v4, v6 []string
	for _, a := range addrs {
		prefix, err := netip.ParsePrefix(a.String())
		if err != nil {
			continue
		}
		ip := prefix.Addr().Unmap()
		if !isPublic(ip) {
			continue
		}
		if ip.Is4() {
			v4 = append(v4, ip.String())
		} else {
			v6 = append(v6, ip.String())
		}
	}
	switch {
	case len(v4) == 1:
		return v4[0], true
	case len(v4) == 0 && len(v6) == 1:
		return v6[0], true
	}
	return "", false
}

// listenPort is the port in a listen address such as ":443" or "[::]:8443".
func listenPort(listen string) int {
	_, port, err := net.SplitHostPort(listen)
	if err != nil {
		return 0
	}
	n, err := strconv.Atoi(port)
	if err != nil {
		return 0
	}
	return n
}
