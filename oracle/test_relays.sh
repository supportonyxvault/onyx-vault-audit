#!/bin/bash
RELAYS=(
"nostr4f4kfhxk5euj3nvfsqavyko2e366mf5a2hzt7spw3k5v74qqad.onion"
"[REDACTED-ONION]"
"[REDACTED-ONION]"
"[REDACTED-ONION]"
"nostr21k3jg2r54gstgznqqxg2d7l3f6sdp7x62qcawkmhsyv6cgqmad.onion"
"[REDACTED-ONION]"
"[REDACTED-ONION]"
"[REDACTED-ONION]"
"[REDACTED-ONION]"
"[REDACTED-ONION]"
)
> /tmp/relay_results.txt
for r in "${RELAYS[@]}"; do
  code=$(curl --socks5-hostname 127.0.0.1:9050 -m 25 -s -o /dev/null -w "%{http_code}" -H "Accept: application/nostr+json" "http://$r/" 2>&1)
  echo "${r:0:30}... : HTTP $code" >> /tmp/relay_results.txt
done
echo "DONE" >> /tmp/relay_results.txt
