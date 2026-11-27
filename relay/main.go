// Command pairline-relay is Pairline's relay for "Your own server" (D-106; docs/PROTOCOL.md, "Your own server (relay,
// D-106)"). It runs on a server the user controls. The bridge phone and the iPhone both connect out to it, and it
// joins their two connections, so calls work away from home even when the home router lets nothing in. Everything
// it passes along is encrypted end to end between the two phones; it can't read or change any of it.
//
// Usage:
//
//	pairline-relay            run the relay (the default)
//	pairline-relay link       print the link and QR code again
//	pairline-relay new-key    replace the access key; links given out before stop working
//	pairline-relay version
//
// Settings come from the environment:
//
//	PAIRLINE_RELAY_PUBLIC   this server's internet address or DNS name, optionally with :port, for the link
//	PAIRLINE_RELAY_LISTEN   where to listen (default :443)
//	PAIRLINE_RELAY_DATA     where the key, certificate and access key live (default ./pairline-relay-data)
//	PAIRLINE_RELAY_MAX_CONNECTIONS  connections at once (default 1000)
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

// version is set at build time (-ldflags "-X main.version=…"); "dev" otherwise.
var version = "dev"

type config struct {
	listen         string
	data           string
	public         string
	maxConnections int
	qr             qrStyle
	// Behind a TCP proxy that passes TLS through untouched (HAProxy, nginx stream, Traefik TCP passthrough): every
	// connection comes from the proxy's address, so the limits per address are off.
	behindProxy bool
}

func configFromEnv() (config, error) {
	c := config{
		listen:         envOr("PAIRLINE_RELAY_LISTEN", ":443"),
		data:           envOr("PAIRLINE_RELAY_DATA", "pairline-relay-data"),
		public:         os.Getenv("PAIRLINE_RELAY_PUBLIC"),
		maxConnections: defaultLimits.maxConnections,
		qr:             qrStyle(envOr("PAIRLINE_RELAY_QR", string(qrANSI))),
	}
	switch c.qr {
	case qrANSI, qrDark, qrLight, qrOff:
	default:
		return c, fmt.Errorf("PAIRLINE_RELAY_QR must be ansi, dark, light or off, not %q", c.qr)
	}
	switch v := os.Getenv("PAIRLINE_RELAY_BEHIND_PROXY"); v {
	case "", "0", "false", "no":
	case "1", "true", "yes":
		c.behindProxy = true
	default:
		return c, fmt.Errorf("PAIRLINE_RELAY_BEHIND_PROXY must be true or false, not %q", v)
	}
	if v := os.Getenv("PAIRLINE_RELAY_MAX_CONNECTIONS"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 2 {
			return c, fmt.Errorf("PAIRLINE_RELAY_MAX_CONNECTIONS must be a number of at least 2, not %q", v)
		}
		c.maxConnections = n
	}
	if listenPort(c.listen) == 0 {
		return c, fmt.Errorf("PAIRLINE_RELAY_LISTEN must be an address with a port, such as :443, not %q", c.listen)
	}
	return c, nil
}

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime)
	cfg, err := configFromEnv()
	if err != nil {
		fail(err)
	}
	command := "serve"
	if len(os.Args) > 1 {
		command = os.Args[1]
	}
	switch command {
	case "serve", "run":
		err = serve(cfg)
	case "link":
		err = printLinkCommand(cfg)
	case "new-key":
		err = newKeyCommand(cfg)
	case "healthcheck":
		err = healthcheck(cfg, 3*time.Second)
	case "version", "--version", "-v":
		fmt.Println("pairline-relay", version)
	case "help", "--help", "-h":
		fmt.Print(usage)
	default:
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	if err != nil {
		fail(err)
	}
}

const usage = `pairline-relay: Pairline's relay for "Your own server"

  pairline-relay            run the relay
  pairline-relay link       print the link and QR code again
  pairline-relay new-key    replace the access key (links given out before stop working)
  pairline-relay healthcheck  exit 0 if the relay here answers with its own certificate (for Docker)
  pairline-relay version

Environment:
  PAIRLINE_RELAY_PUBLIC   this server's internet address or DNS name, optionally with :port
  PAIRLINE_RELAY_LISTEN   where to listen (default :443)
  PAIRLINE_RELAY_DATA     where its key, certificate and access key live (default ./pairline-relay-data)
  PAIRLINE_RELAY_MAX_CONNECTIONS  connections at once (default 1000)
  PAIRLINE_RELAY_QR       how the QR code is drawn: ansi (default, black on white in a terminal), dark or light
                          (plain text for a log viewer with a dark or light background), off
  PAIRLINE_RELAY_BEHIND_PROXY  true behind a TCP proxy that passes TLS through untouched: turns off the limits
                          per address, since every connection comes from the proxy

Guide: relay/README.md in Pairline's source.
`

func fail(err error) {
	fmt.Fprintln(os.Stderr, "pairline-relay:", err)
	os.Exit(1)
}

func serve(cfg config) error {
	id, created, err := loadOrCreateIdentity(cfg.data)
	if err != nil {
		return err
	}
	key, newKey, err := loadOrCreateAccessKey(cfg.data)
	if err != nil {
		return err
	}
	l := defaultLimits
	l.maxConnections = cfg.maxConnections
	l.perAddress = !cfg.behindProxy
	s := newServer(id, key, l, log.Printf)

	ln, err := net.Listen("tcp", cfg.listen)
	if err != nil {
		if errors.Is(err, syscall.EADDRINUSE) {
			return fmt.Errorf("can't listen on %s: something else uses that port (a web server?). "+
				"Set PAIRLINE_RELAY_LISTEN to another port, such as :8443", cfg.listen)
		}
		if errors.Is(err, syscall.EACCES) {
			return fmt.Errorf("can't listen on %s: ports below 1024 need permission. Run it in Docker, give the "+
				"binary CAP_NET_BIND_SERVICE, or set PAIRLINE_RELAY_LISTEN to a port such as :8443", cfg.listen)
		}
		return fmt.Errorf("can't listen on %s: %w", cfg.listen, err)
	}
	log.Printf("pairline-relay %s listening on %s", version, cfg.listen)
	if cfg.behindProxy {
		log.Printf("behind a proxy (PAIRLINE_RELAY_BEHIND_PROXY): connections all come from its address, so the " +
			"limits per address are off; the limit on connections at once still holds")
	}
	if created {
		log.Printf("made a new server key and certificate in %s", cfg.data)
	}
	if newKey {
		log.Printf("made a new access key in %s", cfg.data)
	}
	printLink(cfg, id, key)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go watchAccessKey(ctx, cfg.data, s)
	go reportCounts(ctx, s)
	err = s.serve(ctx, ln)
	log.Printf("stopped")
	return err
}

// watchAccessKey picks up a key replaced with `pairline-relay new-key` while the relay runs.
func watchAccessKey(ctx context.Context, dir string, s *server) {
	path := filepath.Join(dir, accessKeyFile)
	stamp := fileStamp(path)
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		now := fileStamp(path)
		if now == nil || sameStamp(now, stamp) {
			continue
		}
		stamp = now
		key, err := readAccessKey(dir)
		if err != nil {
			log.Printf("the access key file changed but can't be read: %v", err)
			continue
		}
		s.setKey(key)
		log.Printf("the access key changed; links with the old one no longer work")
	}
}

// reportCounts logs how many bridges wait and sessions run whenever that changes, at most every minute, and the
// connections turned away. Never an address, key or rendezvous id.
func reportCounts(ctx context.Context, s *server) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	last := ""
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		line := fmt.Sprintf("%d bridge(s) waiting, %d session(s) running; so far %d joined, %d found no bridge, "+
			"%d wrong key, %d malformed, %d full, %d turned away", s.waitingCount(), s.sessions.Load(),
			s.stats.joined.Load(), s.stats.noBridge.Load(), s.stats.wrongKey.Load(), s.stats.badPreamble.Load(),
			s.stats.full.Load(), s.stats.refused.Load())
		if line != last {
			log.Print(line)
			last = line
		}
	}
}

func printLinkCommand(cfg config) error {
	id, _, err := loadOrCreateIdentity(cfg.data)
	if err != nil {
		return err
	}
	key, _, err := loadOrCreateAccessKey(cfg.data)
	if err != nil {
		return err
	}
	printLink(cfg, id, key)
	return nil
}

func newKeyCommand(cfg config) error {
	id, _, err := loadOrCreateIdentity(cfg.data)
	if err != nil {
		return err
	}
	key, err := newAccessKey(cfg.data)
	if err != nil {
		return err
	}
	fmt.Println("New access key made. Links given out before no longer work; a running relay picks it up within")
	fmt.Println("5 seconds. Set Pairline up again with the new link:")
	printLink(cfg, id, key)
	return nil
}

// printLink prints the link and its QR code to standard output, where `docker logs` shows them.
func printLink(cfg config, id *identity, key [accessKeySize]byte) {
	port := listenPort(cfg.listen)
	host, linkPort := "", port
	detected := false
	if cfg.public != "" {
		h, p, err := publicEndpoint(cfg.public, port)
		if err != nil {
			fmt.Printf("\nPAIRLINE_RELAY_PUBLIC isn't usable (%v).\n", err)
			fmt.Println("Set it to this server's internet address or DNS name, for example 203.0.113.5 or relay.example.com:443.")
			return
		}
		host, linkPort = h, p
	} else if h, ok := detectPublicAddress(); ok {
		host, detected = h, true
	} else {
		fmt.Println()
		fmt.Println("To get the link for Pairline, set PAIRLINE_RELAY_PUBLIC to this server's internet address or DNS")
		fmt.Println("name (the one your phones reach it at), then restart, or run `pairline-relay link` with it set.")
		fmt.Println("With Docker: add  -e PAIRLINE_RELAY_PUBLIC=203.0.113.5  (your server's address) to `docker run`.")
		return
	}
	link := buildLink(host, linkPort, id.fingerprint, key)
	fmt.Println()
	fmt.Println("In Pairline on your iPhone: Settings › Connection › Advanced › Your own server, then scan this code")
	fmt.Println("(or paste the link below). Anyone with this link can use this relay, so keep it to yourself.")
	fmt.Println()
	if cfg.qr != qrOff {
		if qr, err := encodeQR([]byte(link)); err == nil {
			fmt.Print(qr.terminal(cfg.qr))
		}
	}
	fmt.Println()
	fmt.Println(link)
	fmt.Println()
	if detected {
		fmt.Println("(The address was found on this server's network card. If your phones reach it at another address or")
		fmt.Println("name, set PAIRLINE_RELAY_PUBLIC.)")
	}
	if w := addressWarning(host); w != "" {
		fmt.Println("Note:", w+".")
	}
	if cfg.public != "" && linkPort != port {
		fmt.Printf("(The link says port %d and the relay listens on %d: forward %d to it, as Docker's -p does.)\n",
			linkPort, port, linkPort)
	}
}
