#!/usr/bin/env python3
"""Tests for scripts/nap-fetch.py against a local HTTP server (no real network, no real key).
Run: python3 -m unittest scripts/test_nap_fetch.py"""
import http.server
import importlib.util
import io
import json
import os
import tempfile
import threading
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("nap_fetch", os.path.join(HERE, "nap-fetch.py"))
nf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nf)

KEY = "test-key-123"


def make_zip(with_stop_times=True):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr("stops.txt", "stop_id\n")
        if with_stop_times:
            z.writestr("stop_times.txt", "trip_id\n")
    return b.getvalue()


class Handler(http.server.BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        Handler.seen.append((self.path, self.headers.get("ApiKey")))
        if self.headers.get("ApiKey") != KEY:
            self.send_response(401)
            self.end_headers()
            return
        n = self.path.rsplit("/", 1)[1]
        body = {"1": make_zip(), "2": b"<html>login</html>", "3": make_zip(False)}.get(n)
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body)


class NapFetchTest(unittest.TestCase):
    def setUp(self):
        self.srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"
        self.tmp = tempfile.TemporaryDirectory()
        Handler.seen = []

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.tmp.cleanup()

    def manifest(self, feeds):
        p = os.path.join(self.tmp.name, "m.json")
        with open(p, "w") as f:
            json.dump({"id": "t", "feeds": feeds}, f)
        return p

    def feed(self, name, nap):
        d = {"label": name, "file": name + ".zip", "namespace": name, "attribution": "x"}
        if nap is not None:
            d["nap"] = nap
        return d

    def test_header_is_sent_and_only_the_good_feed_is_kept(self):
        m = self.manifest([self.feed("ok", 1), self.feed("html", 2), self.feed("nost", 3), self.feed("gone", 4), self.feed("madrid_style", None)])
        out = os.path.join(self.tmp.name, "out")
        logs = []
        fetched, failed = nf.run(m, out, KEY, self.base, log=logs.append)
        self.assertEqual((1, 3), (fetched, failed))
        self.assertEqual(["ok.zip"], sorted(os.listdir(out)))
        self.assertTrue(all(h == KEY for _, h in Handler.seen))
        self.assertEqual(4, len(Handler.seen))  # the feed without a `nap` id is not requested
        text = "\n".join(logs)
        self.assertIn("HTTP 404", text)
        self.assertIn("not a zip", text)
        self.assertNotIn(KEY, text)

    def test_wrong_or_missing_key_fetches_nothing_and_does_not_raise(self):
        m = self.manifest([self.feed("ok", 1)])
        out = os.path.join(self.tmp.name, "out")
        self.assertEqual((0, 1), nf.run(m, out, "wrong", self.base, log=lambda s: None))
        Handler.seen = []
        self.assertEqual((0, 1), nf.run(m, out, "", self.base, log=lambda s: None))
        self.assertEqual([], Handler.seen)  # without a key nothing is requested
        self.assertEqual([], os.listdir(out))

    def test_shipped_manifests_have_nap_ids_and_mitrams_attribution(self):
        d = os.path.join(HERE, "transit")
        for name in ("barcelona", "valencia", "sevilla", "bilbao"):
            with open(os.path.join(d, name + ".json"), encoding="utf-8") as f:
                m = json.load(f)
            self.assertEqual(name, m["id"])
            for feed in m["feeds"]:
                self.assertIsInstance(feed["nap"], int)
                self.assertIn("Powered by MITRAMS", feed["attribution"])

    def test_madrid_metro_comes_from_nap_file_1134_and_only_that_feed(self):
        with open(os.path.join(HERE, "transit", "madrid.json"), encoding="utf-8") as f:
            m = json.load(f)
        napped = [x for x in m["feeds"] if "nap" in x]
        self.assertEqual(["crtm_metro.zip"], [x["file"] for x in napped])
        self.assertEqual(1134, napped[0]["nap"])
        self.assertIn("Powered by MITRAMS", napped[0]["attribution"])
        self.assertIn("Powered by CRTM", napped[0]["attribution"])

    def test_a_failed_fetch_keeps_a_file_that_was_already_there(self):
        m = os.path.join(self.tmp.name, "m.json")
        with open(m, "w") as f:
            json.dump({"id": "x", "feeds": [{"label": "A", "file": "a.zip", "nap": 1}]}, f)
        out = os.path.join(self.tmp.name, "keep")
        os.makedirs(out)
        with open(os.path.join(out, "a.zip"), "wb") as f:
            f.write(b"old")
        self.assertEqual((0, 1), nf.run(m, out, "wrong", self.base, log=lambda s: None))
        self.assertEqual(["a.zip"], os.listdir(out))


if __name__ == "__main__":
    unittest.main()
