import json, os, sys, requests

API = "https://www.olx.pt/api/v1/offers/"
HEADERS = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
           "Accept": "application/json"}
TOPIC = os.environ["NTFY_TOPIC"]

def load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default

def price_of(offer):
    for p in offer.get("params", []):
        if p.get("key") == "price":
            v = p.get("value", {})
            return v.get("label") or str(v.get("value", "?"))
    return "sem preço"

def notify(search, offer):
    photos = offer.get("photos") or []
    msg = {
        "topic": TOPIC,
        "title": f"{search['name']}: {price_of(offer)}",
        "message": offer.get("title", "Novo anúncio"),
        "click": offer.get("url"),
        "tags": ["motorcycle"],
    }
    if photos:
        msg["attach"] = photos[0]["link"].replace("{width}", "600").replace("{height}", "450")
    requests.post("https://ntfy.sh/", json=msg, timeout=30).raise_for_status()

cfg = load("filters.json", {"searches": []})
seen = load("seen.json", {})
failed = False

for s in cfg["searches"]:
    key = s["name"]
    params = {"offset": 0, "limit": 40, "query": s["query"], "sort_by": "created_at:desc"}
    if s.get("max_price"):
        params["filter_float_price:to"] = s["max_price"]
    if s.get("min_price"):
        params["filter_float_price:from"] = s["min_price"]
    try:
        r = requests.get(API, params=params, headers=HEADERS, timeout=30)
        r.raise_for_status()
        offers = r.json().get("data", [])
    except Exception as e:
        print(f"[{key}] erro: {e}")
        failed = True
        continue

    ids = [str(o["id"]) for o in offers]
    first_run = key not in seen
    known = set(seen.get(key, []))
    if not first_run:
        for o in offers:
            if str(o["id"]) not in known:
                print(f"[{key}] novo: {o.get('title')}")
                notify(s, o)
    else:
        print(f"[{key}] primeira execução: {len(ids)} anúncios registados, sem notificar")
    seen[key] = list(dict.fromkeys(ids + list(known)))[:300]

with open("seen.json", "w", encoding="utf-8") as f:
    json.dump(seen, f, ensure_ascii=False)
sys.exit(1 if failed else 0)
