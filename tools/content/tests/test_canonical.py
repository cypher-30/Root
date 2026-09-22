import unittest

from content_pipeline.canonical import (
    canonical_json_bytes, canonical_json_text, sha256_hex, sha256_of_json,
)


class CanonicalJsonTests(unittest.TestCase):
    def test_key_order_independent(self):
        a = {"b": 1, "a": 2, "c": {"z": 1, "y": 2}}
        b = {"a": 2, "c": {"y": 2, "z": 1}, "b": 1}
        self.assertEqual(canonical_json_bytes(a), canonical_json_bytes(b))

    def test_no_insignificant_whitespace(self):
        text = canonical_json_text({"a": 1, "b": [1, 2, 3]})
        self.assertNotIn(" ", text)
        self.assertNotIn("\n", text)

    def test_unicode_preserved_not_escaped(self):
        text = canonical_json_text({"targetText": "Ndinokuda"})
        self.assertIn("Ndinokuda", text)
        text2 = canonical_json_text({"targetText": "Chii\u0301"})
        self.assertNotIn("\\u", text2)

    def test_deterministic_hash(self):
        obj = {"a": 1, "b": 2}
        h1 = sha256_of_json(obj)
        h2 = sha256_of_json({"b": 2, "a": 1})
        self.assertEqual(h1, h2)
        self.assertEqual(len(h1), 64)

    def test_sha256_hex_matches_known_vector(self):
        # sha256("") is a well-known test vector.
        self.assertEqual(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )


if __name__ == "__main__":
    unittest.main()
