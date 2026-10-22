import importlib.util
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

spec = importlib.util.spec_from_file_location("road", Path(__file__).parents[1] / "road.py")
road = importlib.util.module_from_spec(spec)
spec.loader.exec_module(road)

LINK = "https://yandex.ru/maps?rtext=55.792780%2C37.585656~55.780375%2C37.606505&rtt=mt"
NOW = datetime(2026, 10, 22, 5, 45, tzinfo=road.MOSCOW)


def option(duration, kind="bus", identity="a", age=0):
    return {"uuid": identity, "duration": duration, "matchFilter": True,
            "estimation": {"departureTime": {"value": str(NOW.timestamp() - age)}},
            "paths": [{"segments": [
                {"type": "walk", "duration": {"value": 120}},
                {"type": "transport", "duration": {"value": 400}, "waiting": {"value": 60},
                 "transports": [{"type": kind, "name": "447"}]},
            ]}]}


def page(routes):
    return '<script type="application/json" class="state-view">' + json.dumps(
        {"stack": [{"routerResponse": {"routes": routes}}]}) + '</script>'


class RoutesTest(unittest.TestCase):
    def test_schedule_boundaries_and_weekend(self):
        for hour, minute, expected in [(5, 29, None), (5, 30, "morning"), (6, 59, "morning"),
                                       (7, 0, None), (15, 30, "evening"), (18, 0, None)]:
            with self.subTest(hour=hour, minute=minute):
                self.assertEqual(road.active_slot(NOW.replace(hour=hour, minute=minute)), expected)
        self.assertIsNone(road.active_slot(NOW.replace(day=24)))

    def test_import_drops_fixed_time_and_selection(self):
        url = road.normalize_link("Маршрут " + LINK + "&time=123&ruri=abc", resolve=False)
        self.assertNotIn("time=", url)
        self.assertNotIn("ruri=", url)
        self.assertIn("rtt=mt", url)

    def test_reject_untrusted_urls_and_invalid_points(self):
        for url in [LINK.replace("yandex.ru", "evil.example"), LINK.replace("https:", "http:"),
                    LINK.replace("55.792780", "nan"), LINK.replace("55.792780", "100"),
                    LINK.replace("rtt=mt", "rtt=auto"), LINK.replace("yandex.ru", "yandex.ru@127.0.0.1")]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                road.normalize_link(url, resolve=False)

    def test_fastest_total_duration_not_segment_sum(self):
        slow = option(1500, identity="slow")
        fast = option(1001, identity="fast")
        walk = {"uuid": "walk", "duration": 20, "paths": [{"segments": [{"type": "walk"}]}]}
        result = road.parse_routes(page([slow, fast, fast, walk]), NOW)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["duration_seconds"], 1001)
        self.assertEqual(result[0]["minutes"], 17)
        self.assertEqual(result[0]["walk_minutes"], 2)
        self.assertIn("Автобус", result[0]["summary"])

    def test_stale_upstream_rejected(self):
        with self.assertRaises(ValueError):
            road.parse_routes(page([option(1000, age=3600)]), NOW)
        with self.assertRaises(ValueError):
            road.parse_routes("<html>captcha</html>", NOW)

    def test_failed_refresh_keeps_last_good_result(self):
        def fail(url):
            raise OSError("upstream unavailable")
        with tempfile.TemporaryDirectory() as directory:
            store = road.Store(Path(directory) / "state.json", fetcher=fail)
            store.set_route("morning", "Работа", LINK)
            store.data["cache"]["morning"] = {"best": {"minutes": 17}, "updated_at": 123}
            store.refresh("morning")
            route = store.snapshot()["routes"]["morning"]
            self.assertEqual(route["best"]["minutes"], 17)
            self.assertEqual(route["updated_at"], 123)
            self.assertTrue(route["stale"])

    def test_api_auth_and_input_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            store = road.Store(Path(directory) / "state.json")
            server = road.Server(("127.0.0.1", 0), road.make_handler(store, "secret" * 6))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.server_port}"
            try:
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(base + "/v1/status")
                self.assertEqual(error.exception.code, 401)
                req = urllib.request.Request(base + "/v1/status", headers={"Authorization": "Bearer " + "secret" * 6})
                with urllib.request.urlopen(req) as response:
                    self.assertEqual(json.load(response)["timezone"], "Europe/Moscow")
                req = urllib.request.Request(base + "/v1/routes", data=b'[]', headers={"Authorization": "Bearer " + "secret" * 6})
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(req)
                self.assertEqual(error.exception.code, 400)
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == "__main__":
    unittest.main()
