"""Exercise only this project's local smoke services, without touching real photos."""
import json
import sys
import time
import urllib.request

def request(url, data=None, method=None):
    with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method), timeout=5) as response:
        return response.read()

for port, path in [(15173, '/'), (18080, '/actuator/health'), (18090, '/health'), (19000, '/favicon.ico'), (18025, '/api/v1/info')]:
    for attempt in range(30):
        try:
            request(f'http://127.0.0.1:{port}{path}')
            print(f'HTTP {port}{path}: PASS', flush=True)
            break
        except OSError:
            if attempt == 29:
                raise
            time.sleep(1)

api = json.loads(request('http://127.0.0.1:15173/api/stack'))
assert api == {'status': 'UP', 'database': 'UP', 'records': 1}, api
print('Nginx -> API -> JPA -> PostgreSQL: PASS', flush=True)
ingest = json.loads(request('http://127.0.0.1:18090/health'))
assert ingest['status'] == 'UP' and ingest['mode'] == 'ingestion' and ingest['sharp'], ingest
print('Sharp ingestion health: PASS (pixel/EXIF tests run separately)', flush=True)

url = 'http://127.0.0.1:19000/gallery-smoke/stack-check.txt'
payload = b'gallery local storage smoke check'
if '--check-persistence' in sys.argv:
    assert request(url) == payload
    print('Storage survives container recreation: PASS', flush=True)
request(url, payload, 'PUT')
assert request(url) == payload
print('S3Mock object PUT/GET: PASS (authorization not tested)', flush=True)

print('Mailpit UI/API readiness: PASS (API readiness probe ran above)', flush=True)
