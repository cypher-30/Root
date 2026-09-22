"""Author the DEVELOPMENT-ONLY Shona pilot editorial unit and build its manifest.

This script writes the private editorial authoring registry
(content/editorial/shona_pilot/{phrases,lessons}.json) plus the built,
manifest-only, deterministic pack_builder output at
content/editorial/shona-pilot.json -- the single file the parent packages
via a debug-only Gradle Copy task into the app's content/shona-pilot.json
debug asset. It intentionally does NOT claim publication-readiness: see
README.md in the shona_pilot/ directory and tests/test_shona_pilot.py, which
assert the pipeline's own gates correctly refuse to treat this content as
publishable.

Run: python tools/content/scripts/build_shona_pilot.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "content"))

from content_pipeline.pack_builder import build_manifest  # noqa: E402

PILOT_DIR = ROOT / "content" / "editorial" / "shona_pilot"
MANIFEST_OUTPUT_PATH = ROOT / "content" / "editorial" / "shona-pilot.json"


PROVENANCE = {
    "sourceId": "original",
    "releaseId": "omniglot-shona-phrases-2026-09-15",
    "itemId": None,
    "sourceUrl": "https://www.omniglot.com/language/phrases/shona.php",
    "componentLicense": "UNKNOWN",
    "componentKind": "target_text",
    "requiredCredit": "Phrase contributors Emma Thembani and Ernest Mdende, via Omniglot \"Useful Shona phrases\"",
    "author": "Emma Thembani and Ernest Mdende (Omniglot contributors)",
    "transformationNotes": (
        "Text checked for accuracy against Omniglot on 2026-09-15, matching the "
        "existing app SeedData starter pack. No redistribution license from "
        "Omniglot or the phrase contributors has been obtained; rightsStatus is "
        "honestly recorded as 'unknown', not 'cleared'. This content must never "
        "be published to the public catalog under this provenance record."
    ),
    "rightsStatus": "unknown",
    "reviewStatus": "source_checked",
    "reviewRevision": None,
}


def _credits_from_provenance(provenance):
    """Reduce a rich internal provenance record to the slim wire Credits
    shape (text, license?, sourceUrl?). The manifest never carries the full
    rights/review record -- only the user-facing attribution string plus
    license tag and source URL."""
    return {
        "text": provenance["requiredCredit"] or provenance["sourceId"],
        "license": provenance["componentLicense"],
        "sourceUrl": provenance["sourceUrl"],
    }


def authored_phrase(id_, target_text, meaning, register=None, context=None):
    """Internal editorial authoring record (private registry;
    content/editorial/shona_pilot/phrases.json), richer than the wire
    ManagedPhrase shape -- carries revision/knownLanguageCode/provenance for
    editorial/rights tracking that never ships in a manifest."""
    return {
        "id": id_,
        "revision": 1,
        "targetText": target_text,
        "knownLanguageCode": "en",
        "meaning": meaning,
        "register": register,
        "context": context,
        "audioAssetId": None,
        "provenance": PROVENANCE,
    }


def wire_phrase(authored):
    """Reduce an authored phrase record to the wire ManagedPhrase shape."""
    return {
        "id": authored["id"],
        "prompt": authored["targetText"],
        "meaning": authored["meaning"],
        "audioAssetId": authored["audioAssetId"],
        "register": authored["register"],
        "context": authored["context"],
        "credits": _credits_from_provenance(authored["provenance"]),
    }


AUTHORED_PHRASES = [
    authored_phrase("shona-pilot-hello-sg", "Mhoro", "Hello (one person)", context="greeting one person"),
    authored_phrase("shona-pilot-hello-pl", "Mhoroi", "Hello (more than one person)", context="greeting a group"),
    authored_phrase("shona-pilot-welcome", "Mauya", "Welcome"),
    authored_phrase("shona-pilot-good-morning", "Mangwanani", "Good morning"),
    authored_phrase("shona-pilot-good-afternoon", "Masikati", "Good afternoon"),
    authored_phrase("shona-pilot-good-evening", "Manheru", "Good evening"),
    authored_phrase("shona-pilot-thanks-sg", "Waita zvako", "Thank you (one person)", context="thanking one person"),
    authored_phrase("shona-pilot-thanks-pl", "Maita zvenyu", "Thank you (more than one person)", context="thanking a group"),
]

PHRASES_BY_ID = {p["id"]: p for p in AUTHORED_PHRASES}
WIRE_PHRASES = [wire_phrase(p) for p in AUTHORED_PHRASES]


def dialogue_activity(id_, speaker, phrase_id):
    p = PHRASES_BY_ID[phrase_id]
    return {
        "id": id_,
        "kind": "dialogue_turn",
        "speaker": speaker,
        "text": p["targetText"],
        "translation": p["meaning"],
        "audioAssetId": None,
        "linkedPhraseId": phrase_id,
    }


LESSON_CONVERSATION = {
    "id": "shona-pilot-lesson-greetings",
    "revision": 1,
    "title": "Greetings and Courtesies",
    "objective": (
        "Follow Tendai's day of visits to neighbor Rudo -- a morning arrival, being "
        "welcomed in and giving thanks, an afternoon return, and an evening farewell -- "
        "through six real Shona greeting/courtesy phrases (each used exactly once, in the "
        "time-of-day/register it actually fits), then choose a contextually appropriate reply."
    ),
    "format": "guided_conversation",
    "prerequisiteLessonIds": [],
    "linkedPhraseIds": [p["id"] for p in AUTHORED_PHRASES],
    "activities": [
        {
            # English scene-setup, not a translated/invented Shona claim: explains
            # who is speaking and why the six turns below span morning/afternoon/
            # evening (Shona greetings are genuinely time-of-day specific, so a
            # single continuous exchange cannot naturally use all of them --
            # framing this honestly as three short visits across one day avoids
            # presenting six disconnected phrase cards as one impossible scene).
            # Reflection activities never gate lesson completion (see
            # docs/TEACHING_CONTRACTS.md), so this is informational only.
            "id": "scene-setup",
            "kind": "reflection",
            "prompt": (
                "Setting: Tendai visits their neighbor Rudo three times in one day -- "
                "once in the morning, once in the afternoon, and once at night to say "
                "goodbye. Read the six exchanges below in order, then try the reply "
                "question at the end."
            ),
        },
        dialogue_activity("t1", "Tendai", "shona-pilot-good-morning"),
        dialogue_activity("t2", "Rudo", "shona-pilot-welcome"),
        dialogue_activity("t3", "Tendai", "shona-pilot-thanks-sg"),
        dialogue_activity("t4", "Tendai", "shona-pilot-good-afternoon"),
        dialogue_activity("t5", "Rudo", "shona-pilot-hello-sg"),
        dialogue_activity("t6", "Rudo", "shona-pilot-good-evening"),
        {
            "id": "transfer-1",
            "kind": "choice",
            "task": {
                "id": "transfer-1-task",
                "prompt": "As you leave for the night, Rudo says \"Manheru\" to you. What is the appropriate one-person reply?",
                "choices": [
                    {"id": "c1", "text": "Manheru (Good evening)"},
                    {"id": "c2", "text": "Mangwanani (Good morning)"},
                    {"id": "c3", "text": "Mhoroi (Hello, to a group)"},
                    {"id": "c4", "text": "Masikati (Good afternoon)"},
                ],
                "acceptedChoiceIds": ["c1"],
                "feedbackCorrect": "Correct: mirroring the same evening greeting back is the natural one-to-one reply.",
                "feedbackIncorrect": "Not quite: this reply either names the wrong time of day or is the group form, not a matching one-person evening farewell.",
            },
        },
    ],
    "requiredActivityIds": ["t1", "t2", "t3", "t4", "t5", "t6", "transfer-1"],
}

LESSON_LISTENING = {
    "id": "shona-pilot-lesson-listening",
    "revision": 1,
    "title": "Listening Practice (Audio Unavailable)",
    "objective": "Listening practice for the six greeting/courtesy phrases above (currently blocked: no authentic recording available).",
    "format": "listening",
    "prerequisiteLessonIds": ["shona-pilot-lesson-greetings"],
    "linkedPhraseIds": [p["id"] for p in AUTHORED_PHRASES],
    "activities": [
        {
            "id": "listen-1",
            "kind": "listening",
            "audioAssetId": None,
            "unavailableReason": (
                "No native-speaker or otherwise authentic recording of this phrase set exists yet. "
                "Reusing unrelated benchmark speech or a synthesized voice would misrepresent the "
                "audio as authentic, so listening stays explicitly unavailable until a real, "
                "consenting recording is obtained."
            ),
            "transcript": "Mhoro / Mhoroi / Mauya / Mangwanani / Masikati / Manheru / Waita zvako / Maita zvenyu",
            "translation": "Hello / Hello (group) / Welcome / Good morning / Good afternoon / Good evening / Thank you / Thank you (group)",
            "comprehension": {
                "kind": "choice_task",
                "task": {
                    "id": "listen-1-comprehension",
                    "prompt": "Once a real recording exists: which transcript word means \"Welcome\"?",
                    "choices": [
                        {"id": "w1", "text": "Mhoro"},
                        {"id": "w2", "text": "Mauya"},
                        {"id": "w3", "text": "Manheru"},
                    ],
                    "acceptedChoiceIds": ["w2"],
                    "feedbackCorrect": "Correct: Mauya means Welcome.",
                    "feedbackIncorrect": "Check the transcript/translation pairing above and try again.",
                },
            },
        },
    ],
    # listen-1 IS the required (gating) activity for this lesson -- Kotlin's
    # ContentValidator rejects any lesson with an empty requiredActivityIds
    # (NO_REQUIRED_ACTIVITIES). It is still honestly, permanently
    # un-completable until real audio exists: the LessonRunner returns
    # AUDIO_UNAVAILABLE for a null audioAssetId rather than ever accepting
    # comprehension evidence, so this lesson stays blocked, not silently
    # skippable or fakeable -- "required but cannot complete without audio",
    # not "not required".
    "requiredActivityIds": ["listen-1"],
}

LESSON_PATTERN_WORKSHOP = {
    "id": "shona-pilot-lesson-pattern",
    "revision": 1,
    "title": "Address Pattern Workshop",
    "objective": "Notice how two of these courtesy phrases change when addressing more than one person, then construct both forms.",
    "format": "pattern_workshop",
    "prerequisiteLessonIds": ["shona-pilot-lesson-greetings"],
    "linkedPhraseIds": ["shona-pilot-hello-sg", "shona-pilot-hello-pl", "shona-pilot-thanks-sg", "shona-pilot-thanks-pl"],
    "activities": [
        {
            "id": "explain-1",
            "kind": "pattern_explanation",
            "explanation": (
                "Two of this pack's checked phrase pairs change when you address more than one "
                "person: Mhoro becomes Mhoroi, and Waita zvako becomes Maita zvenyu. This workshop "
                "only demonstrates the pattern for these two already-checked pairs; it is not a "
                "general grammar rule for other Shona words, which would need separate native-speaker "
                "confirmation."
            ),
            "examples": ["shona-pilot-hello-sg", "shona-pilot-hello-pl", "shona-pilot-thanks-sg", "shona-pilot-thanks-pl"],
        },
        {
            "id": "construct-guided-1",
            "kind": "ordered_tokens",
            "task": {
                "id": "construct-guided-1-task",
                "prompt": "Build the one-person greeting.",
                "tokens": [{"occurrenceId": "tok1", "text": "Mhoro"}],
                "acceptedSequences": [["tok1"]],
                "feedbackCorrect": "Right: that is the one-person form.",
                "feedbackIncorrect": "This phrase has only one token to place; check the word itself against the earlier lesson.",
            },
            "assistanceHint": "This activity is supported: the single token is already the whole answer.",
        },
        {
            "id": "construct-changed-context-1",
            "kind": "ordered_tokens",
            "task": {
                "id": "construct-changed-context-1-task",
                "prompt": "Now thank a group of people (not just one person) using the pattern above.",
                "tokens": [
                    {"occurrenceId": "tok1", "text": "Maita"},
                    {"occurrenceId": "tok2", "text": "zvenyu"},
                ],
                "acceptedSequences": [["tok1", "tok2"]],
                "feedbackCorrect": "Right: Maita zvenyu is the group form of Waita zvako, following the same pattern as Mhoro/Mhoroi.",
                "feedbackIncorrect": "Reorder the two words so the group-thanks phrase reads correctly; remember this is the changed-context (plural) counterpart to Waita zvako.",
            },
        },
    ],
    "requiredActivityIds": ["explain-1", "construct-guided-1", "construct-changed-context-1"],
}

AUTHORED_LESSONS = [LESSON_CONVERSATION, LESSON_LISTENING, LESSON_PATTERN_WORKSHOP]


def main():
    result = build_manifest(
        pack_id="pack.shona.pilot.dev",
        version=1,
        language={"id": "sn", "code": "sn", "name": "Shona"},
        title="Shona Pilot (Development Only)",
        objective="Development-only prototype: greetings/courtesies exchange, contextual reply, blocked listening, and a narrow address-pattern workshop.",
        publication_status="development",
        phrases=WIRE_PHRASES,
        lessons=AUTHORED_LESSONS,
        assets=[],
        credits=[
            {
                "text": "Phrase contributors Emma Thembani and Ernest Mdende, via Omniglot \"Useful Shona phrases\" (accuracy-checked, not license-cleared, native-speaker review pending).",
                "license": "UNKNOWN",
                "sourceUrl": "https://www.omniglot.com/language/phrases/shona.php",
            }
        ],
    )

    PILOT_DIR.mkdir(parents=True, exist_ok=True)
    (PILOT_DIR / "phrases.json").write_text(json.dumps(AUTHORED_PHRASES, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (PILOT_DIR / "lessons.json").write_text(json.dumps(AUTHORED_LESSONS, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # Manifest-only build output, separate from the private editorial
    # authoring registry above: this is the single file the parent copies
    # (debug-only Gradle Copy task) into the app's content/shona-pilot.json
    # debug asset. No publication claim is made by writing this file.
    MANIFEST_OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_OUTPUT_PATH.write_bytes(result.manifest_bytes)
    print("Wrote", MANIFEST_OUTPUT_PATH, "sha256=" + result.manifest_sha256, "bytes=" + str(len(result.manifest_bytes)))


if __name__ == "__main__":
    main()
