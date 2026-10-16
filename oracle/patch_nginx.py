#!/usr/bin/env python3
p = '/etc/nginx/sites-enabled/[REDACTED-VM-HOSTNAME]'
c = open(p).read()
if 'location /relay-publish' in c:
    print('already has relay-publish')
else:
    inject = """
    location /relay-publish {
        proxy_pass http://127.0.0.1:3000/relay-publish;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        add_header Access-Control-Allow-Origin * always;
        add_header Access-Control-Allow-Methods "POST, OPTIONS" always;
        add_header Access-Control-Allow-Headers "Content-Type" always;
    }
    location /relay-health {
        proxy_pass http://127.0.0.1:3000/health;
    }
"""
    c = c.replace('    location / {', inject + '    location / {')
    open(p, 'w').write(c)
    print('nginx config updated')
