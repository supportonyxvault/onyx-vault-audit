#!/bin/bash
# End-to-End Test: POST einen Dummy-Event an /relay-publish
EVENT='{"kind":30078,"created_at":1760345000,"tags":[["d","onyx-test"]],"content":"test","pubkey":"0000000000000000000000000000000000000000000000000000000000000000","id":"0000000000000000000000000000000000000000000000000000000000000000","sig":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}'

echo "Teste /relay-publish mit Dummy-Event (max 90s)..."
curl -s -X POST -H "Content-Type: application/json" --max-time 90 -d "$EVENT" http://127.0.0.1:8080/relay-publish > /tmp/publish_result.json
cat /tmp/publish_result.json
echo "DONE"
