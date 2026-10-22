"""Now Bar Road personal commute server. Python 3.11+, standard library only."""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import math
import os
import re
import argparse
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

MOSCOW = ZoneInfo("Europe/Moscow")
SLOTS = {"morning": (330, 420), "evening": (930, 1080)}
MAX_HTML = 6 * 1024 * 1024
LOG = logging.getLogger("now-bar-road")


def active_slot(now=None):
    now = (now or datetime.now(MOSCOW)).astimezone(MOSCOW)
    if now.weekday() >= 5:
        return None
    minute = now.hour * 60 + now.minute
    return next((slot for slot, (start, end) in SLOTS.items() if start <= minute < end), None)


def prefetch_slot(now=None):
    # Prepare data two minutes before the phone's notification window.
    return active_slot((now or datetime.now(MOSCOW)) + timedelta(minutes=2))


class PublicYandexRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_url(url):
    p = urllib.parse.urlsplit(url)
    if (p.scheme != "https" or p.hostname not in {"yandex.ru", "www.yandex.ru", "yandex.com", "www.yandex.com"}
            or p.port not in {None, 443} or p.username or p.password):
        raise ValueError("Нужна HTTPS-ссылка Яндекс Карт на yandex.ru или yandex.com")
    if not (p.path == "/maps" or p.path.startswith("/maps/")):
        raise ValueError("Поддерживаются ссылки Яндекс Карт /maps")
    return p


def fetch_page(url):
    validate_url(url)
    opener = urllib.request.build_opener(PublicYandexRedirect())
    request = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36",
        "Accept-Language": "ru-RU,ru;q=0.9", "Cache-Control": "no-cache",
    })
    with opener.open(request, timeout=15) as response:
        data = response.read(MAX_HTML + 1)
        if len(data) > MAX_HTML:
            raise ValueError("Ответ источника слишком большой")
        return response.url, data.decode("utf-8")


def normalize_link(text, resolve=True):
    match = re.search(r"https://[^\s<>]+", text)
    if not match:
        raise ValueError("Вставьте ссылку Яндекс Карт")
    url = match.group(0)
    p = validate_url(url)
    query = urllib.parse.parse_qs(p.query)
    if "rtext" not in query and resolve:
        url, _ = fetch_page(url)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
    raw = query.get("rtext", [""])[0]
    points = raw.split("~")
    if not 2 <= len(points) <= 10:
        raise ValueError("Ссылка должна содержать от 2 до 10 точек маршрута (rtext)")
    canonical = []
    for point in points:
        try:
            lat, lon = map(float, point.split(","))
        except ValueError as exc:
            raise ValueError("В ссылке нужны координаты точек, а не текстовые адреса") from exc
        if not (math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError("Некорректные координаты")
        canonical.append(f"{lat:.6f},{lon:.6f}")
    if query.get("rtt", [""])[0] != "mt":
        raise ValueError("Пока поддерживается общественный транспорт: выберите его в Яндекс Картах")
    # Drop fixed departure times and selected alternatives: always calculate 'now'.
    return "https://yandex.ru/maps?" + urllib.parse.urlencode({"rtext": "~".join(canonical), "rtt": "mt"})


class StateParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.recording = False
        self.parts = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "script" and "state-view" in a.get("class", "").split():
            self.recording = True

    def handle_data(self, data):
        if self.recording:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "script":
            self.recording = False


def route_objects(value):
    if isinstance(value, dict):
        response = value.get("routerResponse")
        if isinstance(response, dict) and isinstance(response.get("routes"), list):
            yield from response["routes"]
        else:
            for child in value.values():
                yield from route_objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from route_objects(child)


def parse_routes(page, now=None):
    parser = StateParser()
    parser.feed(page)
    if not parser.parts:
        raise ValueError("Яндекс не вернул данные маршрута: возможна CAPTCHA или изменение сайта")
    state = json.loads("".join(parser.parts))
    now = now or datetime.now(MOSCOW)
    results = []
    seen = set()
    labels = {"bus": "Автобус", "underground": "Метро", "tramway": "Трамвай",
              "trolleybus": "Троллейбус", "railway": "Поезд", "minibus": "Маршрутка"}
    for route in route_objects(state):
        identity = route.get("uuid") or route.get("uri")
        if identity and identity in seen:
            continue
        seen.add(identity)
        duration = route.get("duration")
        if not isinstance(duration, (int, float)) or isinstance(duration, bool) or not 0 < duration < 172800:
            continue
        if route.get("matchFilter") is False:
            continue
        segments = [seg for path in route.get("paths", []) for seg in path.get("segments", [])]
        # Public transport mode can include walking-only alternatives. Keep actual transit trips.
        if not any(seg.get("type") == "transport" for seg in segments):
            continue
        estimation = route.get("estimation", {})
        departure = estimation.get("departureTime", {}).get("value")
        if departure is not None:
            try:
                age = now.timestamp() - float(departure)
            except (ValueError, TypeError):
                continue
            if age > 600 or age < -600:
                continue
        summary = []
        for seg in segments:
            transports = seg.get("transports", [])
            if transports:
                kind = labels.get(transports[0].get("type"), "Транспорт")
                names = "/".join(dict.fromkeys(str(t.get("name", "")) for t in transports))
                summary.append(f"{kind} {names}".strip())
        walk_seconds = sum(seg.get("duration", {}).get("value", 0) for seg in segments
                           if seg.get("type") in {"walk", "transfer"})
        results.append({
            "duration_seconds": math.ceil(duration),
            "minutes": math.ceil(duration / 60),
            "summary": " → ".join(summary) or "Общественный транспорт",
            "walk_minutes": math.ceil(walk_seconds / 60),
            "arrival": estimation.get("arrivalTime", {}).get("text"),
            "departure_at": departure,
        })
    if not results:
        raise ValueError("Нет актуальных вариантов общественного транспорта; старые данные не выдаются как свежие")
    return sorted(results, key=lambda item: item["duration_seconds"])


class Store:
    def __init__(self, path, fetcher=fetch_page):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.fetch_lock = threading.Lock()
        self.fetcher = fetcher
        self.data = {"routes": {}, "cache": {}}
        if self.path.exists():
            self.data = json.loads(self.path.read_text())
        self.attempts = {}

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")
        temp.chmod(0o600)
        temp.replace(self.path)

    def set_route(self, slot, name, link):
        if slot not in SLOTS:
            raise ValueError("Неизвестный слот")
        url = normalize_link(link)
        with self.lock:
            old = self.data["routes"].get(slot, {})
            self.data["routes"][slot] = {"name": str(name).strip()[:80] or slot, "url": url}
            if old.get("url") != url:
                self.data["cache"].pop(slot, None)
                self.attempts.pop(slot, None)
            self.save()

    def refresh(self, slot):
        # Serialize upstream requests and do not let manual refresh hammer the website.
        with self.fetch_lock:
            with self.lock:
                route = self.data["routes"].get(slot)
                if not route:
                    return
                route = dict(route)
                key = (slot, route["url"])
                if time.monotonic() - self.attempts.get(key, -1e9) < 300:
                    return
                self.attempts[key] = time.monotonic()
            try:
                _, page = self.fetcher(route["url"])
                options = parse_routes(page)
                update = {"best": options[0], "alternatives": options, "updated_at": time.time(),
                          "error": None, "source": "yandex_web"}
            except (ValueError, OSError, urllib.error.URLError, KeyError, TypeError) as exc:
                LOG.warning("Route refresh failed (%s): %s", slot, type(exc).__name__)
                update = {"error": "Источник временно недоступен или формат ответа изменился",
                          "attempted_at": time.time()}
            with self.lock:
                if self.data["routes"].get(slot) != route:
                    return
                self.data["cache"].setdefault(slot, {}).update(update)
                self.save()

    def snapshot(self):
        with self.lock:
            routes = {}
            for slot, route in self.data["routes"].items():
                cache = dict(self.data["cache"].get(slot, {}))
                cache["stale"] = (bool(cache.get("error")) or
                                   time.time() - cache.get("updated_at", 0) > 600)
                routes[slot] = {**route, **cache}
            return {"timezone": "Europe/Moscow", "active_slot": active_slot(),
                    "server_time": time.time(), "routes": routes}


def make_handler(store, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            # Never write bearer tokens, route coordinates or request bodies to logs.
            LOG.info("HTTP %s", args[1] if len(args) > 1 else "request")

        def send_json(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            supplied = self.headers.get("Authorization", "")
            return hmac.compare_digest(hashlib.sha256(supplied.encode()).digest(),
                                       hashlib.sha256(f"Bearer {token}".encode()).digest())

        def do_GET(self):
            if self.path == "/health":
                self.send_json(200, {"ok": True})
            elif not self.authorized():
                self.send_json(401, {"error": "Неверный токен"})
            elif self.path == "/v1/status":
                self.send_json(200, store.snapshot())
            else:
                self.send_json(404, {"error": "Not found"})

        def do_POST(self):
            if not self.authorized():
                self.send_json(401, {"error": "Неверный токен"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    raise ValueError("Недопустимый размер запроса")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("Нужен JSON-объект")
                if self.path == "/v1/routes":
                    slot = body.get("slot")
                    if not isinstance(body.get("url"), str):
                        raise ValueError("Нужна ссылка маршрута")
                    store.set_route(slot, body.get("name", ""), body["url"])
                    store.refresh(slot)
                elif self.path == "/v1/refresh":
                    slot = body.get("slot") or active_slot()
                    if slot not in SLOTS:
                        raise ValueError("Укажите morning или evening")
                    store.refresh(slot)
                else:
                    self.send_json(404, {"error": "Not found"})
                    return
                self.send_json(200, store.snapshot())
            except (ValueError, TypeError, urllib.error.URLError, OSError) as exc:
                self.send_json(400, {"error": str(exc)[:200]})

    return Handler


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def get_request(self):
        sock, address = super().get_request()
        sock.settimeout(30)
        return sock, address


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = argparse.ArgumentParser(description="Now Bar Road server")
    args.add_argument("--connection-link", action="store_true", help="Print Android connection link")
    options = args.parse_args()
    token = os.environ.get("ROAD_TOKEN", "")
    if len(token) < 24:
        raise SystemExit("Set ROAD_TOKEN to a random token of at least 24 characters")
    if options.connection_link:
        base = os.environ.get("ROAD_PUBLIC_URL", "").rstrip("/")
        p = urllib.parse.urlsplit(base)
        if p.scheme != "https" or not p.hostname or p.username or p.password or p.query or p.fragment:
            raise SystemExit("Set ROAD_PUBLIC_URL to your HTTPS server address")
        print("nowbarroad://connect?" + urllib.parse.urlencode({"server": base, "token": token}))
        return
    store = Store(os.environ.get("ROAD_DATA", "data/state.json"))
    stop = threading.Event()

    def poll():
        while not stop.is_set():
            slot = active_slot() or prefetch_slot()
            if slot:
                try:
                    store.refresh(slot)
                except OSError:
                    LOG.exception("Cannot persist route cache")
            stop.wait(30)

    threading.Thread(target=poll, daemon=True).start()
    server = Server((os.environ.get("ROAD_HOST", "127.0.0.1"),
                     int(os.environ.get("ROAD_PORT", "8080"))), make_handler(store, token))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()


if __name__ == "__main__":
    main()
