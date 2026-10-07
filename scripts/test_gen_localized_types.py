"""Tests for gen-localized-types.py: python3 -I -m unittest discover -s scripts -p 'test_*.py'"""
import importlib.util
import os
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location("gen", os.path.join(os.path.dirname(__file__), "gen-localized-types.py"))
gen = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gen)

EN = '/* c */\n"type.amenity.pharmacy" = "Pharmacy";\n\n"type.shop.say" = "Say \\"hi\\"";\n"type.empty" = "";\n'
ES = '"type.amenity.pharmacy" = "Farmacia";\n"type.amenity.cafe" = "Cafetería";\n'


def write_strings(root, lang, text):
    d = os.path.join(root, "iphone", "Maps", "LocalizedStrings", f"{lang}.lproj")
    os.makedirs(d)
    with open(os.path.join(d, "LocalizableTypes.strings"), "w", encoding="utf-8") as f:
        f.write(text)


class GenLocalizedTypesTest(unittest.TestCase):
    def test_parse_keeps_escapes_and_skips_comments_and_empty_values(self):
        self.assertEqual({"type.amenity.pharmacy": "Pharmacy", "type.shop.say": 'Say \\"hi\\"'}, gen.parse(EN))

    def test_render_is_sorted_and_per_language(self):
        out = gen.render({"es": {"b": "2", "a": "1"}, "en": {"a": "x"}})
        self.assertLess(out.index('"en"'), out.index('"es"'))
        self.assertLess(out.index('{"a", "1"}'), out.index('{"b", "2"}'))

    def test_load_skips_a_missing_language_with_a_warning(self):
        with tempfile.TemporaryDirectory() as d:
            write_strings(d, "en", EN)
            write_strings(d, "es", ES)
            logs = []
            tables = gen.load(d, ["en", "es", "gl"], log=logs.append)
            self.assertEqual({"en", "es"}, set(tables))
            self.assertEqual("Farmacia", tables["es"]["type.amenity.pharmacy"])
            self.assertEqual(1, len(logs))

    def test_main_requires_english(self):
        with tempfile.TemporaryDirectory() as d:
            write_strings(d, "es", ES)
            with self.assertRaises(SystemExit):
                gen.main(["--comaps", d, "--langs", "es", "-o", os.path.join(d, "o.inc")])

    def test_main_writes_the_file(self):
        with tempfile.TemporaryDirectory() as d:
            write_strings(d, "en", EN)
            write_strings(d, "es", ES)
            out = os.path.join(d, "g", "o.inc")
            gen.main(["--comaps", d, "-o", out])
            with open(out, encoding="utf-8") as f:
                text = f.read()
            self.assertIn('{"type.amenity.cafe", "Cafetería"}', text)

    def test_rejects_a_path_like_language(self):
        with self.assertRaises(SystemExit):
            gen.load("/nonexistent", ["../x"])


if __name__ == "__main__":
    unittest.main()
