p='/etc/nginx/sites-enabled/[REDACTED-VM-HOSTNAME]'
c=open(p).read()
# Fix broken CSP, allow cdnjs + pdf.worker blob
import re
new = 'script-src \'self\' \'unsafe-inline\' https://cdnjs.cloudflare.com blob:'
c = re.sub(r"script-src[^;]+;", new + ';', c)
# Also allow blob for pdf.js worker
c = c.replace("default-src 'self';", "default-src 'self' blob:;")
open(p,'w').write(c)
print('CSP fixed')
