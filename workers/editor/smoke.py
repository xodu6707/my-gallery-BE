"""Check Python and optional local ComfyUI; this does not process edit jobs."""
import json
import os
import urllib.error
import urllib.request

url = os.environ.get("COMFYUI_URL", "http://127.0.0.1:8188").rstrip("/")
result = {"python": "UP", "mode": "smoke-only", "comfyui": "NOT_READY", "editingExecuted": False}
try:
    with urllib.request.urlopen(url + "/system_stats", timeout=3) as response:
        stats = json.load(response)
        result["comfyui"] = "UP" if "system" in stats else "UNEXPECTED_RESPONSE"
except (urllib.error.URLError, TimeoutError, ValueError):
    result["action"] = "Start the dedicated ComfyUI instance on localhost:8188."
print(json.dumps(result))
