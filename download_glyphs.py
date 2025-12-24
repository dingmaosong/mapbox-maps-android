#!/usr/bin/env python3
import os
import sys
import requests

# ====== 可配置参数 ======
# MAPBOX_ACCESS_TOKEN = "替换成你的access_token"
MAPBOX_ACCESS_TOKEN = "pk.eyJ1IjoiaGNpYWxsIiwiYSI6ImNrcjJ4MnpuazJnNWsyb21uMHltZ2Q3NzcifQ.BUU4H1KNAzI6khKkLZR89Q"
FONT_STACK = "Open Sans Regular"
START = 0
END = 65535
STEP = 256

# =======================

BASE_URL = "https://api.mapbox.com/fonts/v1/mapbox"
OUTPUT_DIR = os.path.join("glyphs", FONT_STACK)

def download_range(start):
    end = start + STEP - 1
    url = (
        f"{BASE_URL}/"
        f"{FONT_STACK.replace(' ', '%20')}/"
        f"{start}-{end}.pbf"
        f"?access_token={MAPBOX_ACCESS_TOKEN}"
    )

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    output_file = os.path.join(OUTPUT_DIR, f"{start}-{end}.pbf")

    if os.path.exists(output_file):
        print(f"[SKIP] {output_file}")
        return

    print(f"[DOWNLOADING] {start}-{end}.pbf")
    r = requests.get(url, timeout=30)

    if r.status_code != 200:
        print(f"[ERROR] {start}-{end} -> HTTP {r.status_code}")
        return

    with open(output_file, "wb") as f:
        f.write(r.content)

def main():
    for start in range(START, END + 1, STEP):
        download_range(start)

    print("\n✅ 所有 glyph 下载完成")

if __name__ == "__main__":
    try:
        import requests
    except ImportError:
        print("❌ 缺少 requests 库，执行: pip3 install requests")
        sys.exit(1)

    main()
