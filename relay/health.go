package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"strconv"
	"time"
)

// healthcheck is `pairline-relay healthcheck`, for Docker's and Coolify's health checks: the image has no shell or
// curl, so the relay checks itself. It connects to the port it listens on inside the container and finishes a TLS
// handshake, accepted only with this relay's own certificate, then hangs up without a preamble (which the relay
// neither answers nor counts).
func healthcheck(cfg config, timeout time.Duration) error {
	id, _, err := loadOrCreateIdentity(cfg.data)
	if err != nil {
		return err
	}
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(listenPort(cfg.listen)))
	dialer := &net.Dialer{Timeout: timeout}
	conn, err := tls.DialWithDialer(dialer, "tcp", addr, &tls.Config{
		InsecureSkipVerify: true, // the pin below is the check, as on the phones
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 || sha256.Sum256(raw[0]) != id.fingerprint {
				return errors.New("another certificate answered on this port")
			}
			return nil
		},
	})
	if err != nil {
		return fmt.Errorf("unhealthy: %w", err)
	}
	return conn.Close()
}
