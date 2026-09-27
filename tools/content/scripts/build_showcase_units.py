"""Author the bundled showcase units (two per language) and build their manifests.

Every target-language line is copied from the starter phrases in
app/src/main/kotlin/com/root/app/data/SeedCatalog.kt, which were checked
against the public references credited below. Stories frame those phrases
with English narration instead of inventing new target-language sentences.
None of this has had native-speaker review yet, and there are no authentic
recordings: each story's listening step is optional and says so, rather than
using synthetic or unrelated audio.

Outputs content/editorial/showcase/<pack-id>.json (deterministic). The
debug/validation Gradle copy task bundles them next to shona-pilot.json.

Run: python tools/content/scripts/build_showcase_units.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "content"))

from content_pipeline.pack_builder import build_manifest  # noqa: E402

OUTPUT_DIR = ROOT / "content" / "editorial" / "showcase"

AUDIO_UNAVAILABLE = (
    "There is no genuine recording of these lines yet. Root will not stand in a "
    "synthetic or unrelated voice, so this listening step opens once a real "
    "speaker has recorded it. The story and its question above count on their own."
)

LANGUAGES = {
    "sn": {"id": "sn", "code": "sn", "name": "Shona"},
    "sw": {"id": "sw", "code": "sw", "name": "Swahili"},
    "am": {"id": "am", "code": "am", "name": "Amharic"},
    "luo": {"id": "luo", "code": "luo", "name": "Dholuo"},
}

CREDITS = {
    "sn": {
        "text": "Phrases from Omniglot \"Useful Shona phrases\" (Emma Thembani and Ernest Mdende) and Wiktionary; accuracy-checked, native-speaker review pending.",
        "license": "UNKNOWN",
        "sourceUrl": "https://www.omniglot.com/language/phrases/shona.php",
    },
    "sw": {
        "text": "Phrases from Wikivoyage contributors, \"Swahili phrasebook\", and Omniglot \"Useful Swahili phrases\"; native-speaker review pending.",
        "license": "CC BY-SA 4.0",
        "sourceUrl": "https://en.wikivoyage.org/wiki/Swahili_phrasebook",
    },
    "am": {
        "text": "Phrases from the FSI Amharic Basic Course (public domain), Wikivoyage contributors \"Amharic phrasebook\", and Omniglot; native-speaker review pending.",
        "license": "CC BY-SA 4.0",
        "sourceUrl": "https://en.wikivoyage.org/wiki/Amharic_phrasebook",
    },
    "luo": {
        "text": "Phrases from Wikivoyage contributors, \"Luo phrasebook\", spelling normalized to dh; native-speaker review pending.",
        "license": "CC BY-SA 4.0",
        "sourceUrl": "https://en.wikivoyage.org/wiki/Luo_phrasebook",
    },
}


def turn(id_, speaker, text, translation):
    return {
        "id": id_,
        "kind": "dialogue_turn",
        "speaker": speaker,
        "text": text,
        "translation": translation,
        "audioAssetId": None,
        "linkedPhraseId": None,
    }


def narration(id_, text):
    return {
        "id": id_,
        "kind": "dialogue_turn",
        "speaker": "Story",
        "text": text,
        "translation": None,
        "audioAssetId": None,
        "linkedPhraseId": None,
    }


def choice_task(id_, prompt, options, correct, feedback_correct, feedback_incorrect):
    choices = [{"id": "c" + str(i + 1), "text": text} for i, text in enumerate(options)]
    return {
        "id": id_,
        "prompt": prompt,
        "choices": choices,
        "acceptedChoiceIds": ["c" + str(options.index(correct) + 1)],
        "feedbackCorrect": feedback_correct,
        "feedbackIncorrect": feedback_incorrect,
    }


def choice(id_, prompt, options, correct, feedback_correct, feedback_incorrect):
    return {
        "id": id_,
        "kind": "choice",
        "task": choice_task(id_ + "-task", prompt, options, correct, feedback_correct, feedback_incorrect),
    }


def build(id_, prompt, shuffled, answer, feedback_correct, feedback_incorrect, hint=None):
    """Ordered-token construction. [shuffled] is the display order; [answer]
    is the accepted order of the same words."""
    assert sorted(shuffled) == sorted(answer), (shuffled, answer)
    tokens = [{"occurrenceId": "tok" + str(i + 1), "text": t} for i, t in enumerate(shuffled)]
    remaining = list(tokens)
    sequence = []
    for word in answer:
        match = next(t for t in remaining if t["text"] == word)
        remaining.remove(match)
        sequence.append(match["occurrenceId"])
    activity = {
        "id": id_,
        "kind": "ordered_tokens",
        "task": {
            "id": id_ + "-task",
            "prompt": prompt,
            "tokens": tokens,
            "acceptedSequences": [sequence],
            "feedbackCorrect": feedback_correct,
            "feedbackIncorrect": feedback_incorrect,
        },
    }
    if hint:
        activity["assistanceHint"] = hint
    return activity


def explain(id_, explanation, examples):
    return {"id": id_, "kind": "pattern_explanation", "explanation": explanation, "examples": examples}


def reflection(id_, prompt):
    return {"id": id_, "kind": "reflection", "prompt": prompt}


def speaking(id_, prompt):
    return {"id": id_, "kind": "speaking_prompt", "prompt": prompt}


def listening(id_, transcript, translation, question, options, correct):
    return {
        "id": id_,
        "kind": "listening",
        "audioAssetId": None,
        "unavailableReason": AUDIO_UNAVAILABLE,
        "transcript": transcript,
        "translation": translation,
        "comprehension": {
            "kind": "choice_task",
            "task": choice_task(id_ + "-comprehension", question, options, correct,
                                "Correct.", "Listen again and check the transcript."),
        },
    }


def lesson(id_, title, objective, fmt, activities, required, prerequisites=()):
    ids = {a["id"] for a in activities}
    assert set(required) <= ids, (id_, set(required) - ids)
    return {
        "id": id_,
        "revision": 1,
        "title": title,
        "objective": objective,
        "format": fmt,
        "prerequisiteLessonIds": list(prerequisites),
        "linkedPhraseIds": [],
        "activities": activities,
        "requiredActivityIds": list(required),
    }


def unit(pack_id, lang, title, objective, conversation, pattern, story):
    """Three lessons: conversation first; the workshop and story build on it."""
    conv_id, pat_id, story_id = pack_id + "-conversation", pack_id + "-pattern", pack_id + "-story"

    def prefixed(prefix, activities):
        return [dict(a, id=prefix + "-" + a["id"]) for a in activities]

    conv_acts = prefixed(conv_id, conversation["activities"])
    pat_acts = prefixed(pat_id, pattern["activities"])
    story_acts = prefixed(story_id, story["activities"])

    def required(acts, optional_kinds=("reflection", "speaking_prompt", "listening")):
        return [a["id"] for a in acts if a["kind"] not in optional_kinds]

    lessons = [
        lesson(conv_id, conversation["title"], conversation["objective"], "guided_conversation",
               conv_acts, required(conv_acts)),
        lesson(pat_id, pattern["title"], pattern["objective"], "pattern_workshop",
               pat_acts, required(pat_acts), [conv_id]),
        lesson(story_id, story["title"], story["objective"], "listening",
               story_acts, required(story_acts), [conv_id]),
    ]
    return {
        "pack_id": pack_id,
        "language": LANGUAGES[lang],
        "title": title,
        "objective": objective,
        "lessons": lessons,
        "credits": [CREDITS[lang]],
    }


UNITS = [
    # ------------------------------------------------------------------ Shona
    unit(
        "pack.shona.meeting", "sn",
        "Meeting people and getting around",
        "Introduce yourself, ask where someone is from, and handle a journey politely.",
        conversation={
            "title": "Meeting Chipo",
            "objective": "Follow Tendai and Chipo introducing themselves, then pick the right reply to a name question.",
            "activities": [
                reflection("setup", "Tendai meets Chipo for the first time at a friend's house. Read their exchange in order."),
                turn("t1", "Tendai", "Mhoro", "Hello (one person)"),
                turn("t2", "Chipo", "Wakadini zvako?", "How are you? (one person)"),
                turn("t3", "Tendai", "Ndiripo", "I'm fine"),
                turn("t4", "Chipo", "Unonzani?", "What's your name?"),
                turn("t5", "Tendai", "Ndinonzi Tendai", "My name is Tendai"),
                turn("t6", "Chipo", "Unobva kupi?", "Where are you from?"),
                turn("t7", "Tendai", "Ndinobva kuHarare", "I'm from Harare"),
                turn("t8", "Chipo", "Ndafara kukuziva", "Pleased to meet you"),
                choice("reply", "Chipo's cousin asks you \"Unonzani?\". Which reply fits?",
                       ["Ndinonzi …", "Ndinobva ku…", "Sara zvakanaka"], "Ndinonzi …",
                       "Right: Ndinonzi … (My name is …) answers Unonzani?",
                       "That answers a different question. Unonzani? asks for your name."),
                speaking("say", "Say the whole introduction aloud with your own name and home town."),
            ],
        },
        pattern={
            "title": "You and I: questions and answers",
            "objective": "Notice how a question to you and its answer about me differ in these checked pairs, then build both.",
            "activities": [
                explain("explain",
                        "In these two checked pairs the question to one person starts with U- (you) and the answer "
                        "starts with Ndi- (I). Treat this as a note about these phrases only, not a rule for every "
                        "Shona verb; that needs a native speaker to confirm.",
                        ["Unonzani? (What are you called?) → Ndinonzi … (I am called …)",
                         "Unobva kupi? (Where are you from?) → Ndinobva ku… (I come from …)"]),
                build("build-1", "Build \"I'm from Harare\".", ["kuHarare", "Ndinobva"], ["Ndinobva", "kuHarare"],
                      "Right: Ndinobva kuHarare.", "Start with the I-form, Ndinobva."),
                build("build-2", "Ask someone to say it again, politely.",
                      ["futi", "kuti", "Ndinokumbirawo", "muzvitaure"],
                      ["Ndinokumbirawo", "kuti", "muzvitaure", "futi"],
                      "Right: Ndinokumbirawo kuti muzvitaure futi — please say that again.",
                      "It opens with the polite request, Ndinokumbirawo, and ends with futi (again).",
                      hint="Ndinokumbirawo means 'I kindly ask'."),
            ],
        },
        story={
            "title": "Story: Chipo's bus ride",
            "objective": "Read a short journey told through phrases you know, then answer a question about it.",
            "activities": [
                narration("n1", "Chipo is leaving for Bulawayo. At the door her grandmother says:"),
                turn("s1", "Grandmother", "Ufambe zvakanaka", "Have a good journey"),
                narration("n2", "On the bus a man gives her directions, but he talks very fast. Chipo says:"),
                turn("s2", "Chipo", "Ndinokumbirawo kuti musakurumidze kutaura", "Please speak more slowly"),
                narration("n3", "At the next stop she needs the toilet and asks:"),
                turn("s3", "Chipo", "Chimbuzi chiripi?", "Where's the toilet?"),
                narration("n4", "The bus starts to pull away without her friend, so she calls out:"),
                turn("s4", "Chipo", "Mira!", "Stop!"),
                choice("check", "What did Chipo say when the man spoke too fast?",
                       ["Ndinokumbirawo kuti musakurumidze kutaura", "Ufambe zvakanaka", "Mira!"],
                       "Ndinokumbirawo kuti musakurumidze kutaura",
                       "Right: she asked him to speak more slowly.",
                       "Look again at what Chipo said after the fast directions."),
                listening("listen", "Ufambe zvakanaka / Chimbuzi chiripi? / Mira!",
                          "Have a good journey / Where's the toilet? / Stop!",
                          "Which line asks for the toilet?", ["Mira!", "Chimbuzi chiripi?", "Ufambe zvakanaka"],
                          "Chimbuzi chiripi?"),
            ],
        },
    ),
    # ------------------------------------------------------------------ Swahili
    unit(
        "pack.swahili.meeting", "sw",
        "Hello, who are you?",
        "Greet someone, trade names and home towns, and say when you don't understand.",
        conversation={
            "title": "Amina meets Juma",
            "objective": "Follow a first conversation in Mombasa, then choose the reply to a name question.",
            "activities": [
                reflection("setup", "Amina is new in Mombasa. Her neighbour Juma greets her in the morning."),
                turn("t1", "Juma", "Habari ya asubuhi", "Good morning"),
                turn("t2", "Amina", "Nzuri, asante", "Fine, thank you"),
                turn("t3", "Juma", "Jina lako ni nani?", "What is your name?"),
                turn("t4", "Amina", "Jina langu ni Amina", "My name is Amina"),
                turn("t5", "Juma", "Unatoka wapi?", "Where are you from?"),
                turn("t6", "Amina", "Ninatoka Nairobi", "I'm from Nairobi"),
                turn("t7", "Juma", "Nimefurahi kukutana nawe", "Pleased to meet you"),
                choice("reply", "Someone asks you \"Jina lako ni nani?\". Which reply fits?",
                       ["Jina langu ni …", "Ninatoka …", "Kwaheri"], "Jina langu ni …",
                       "Right: Jina langu ni … (My name is …).",
                       "Jina lako ni nani? asks for your name."),
                speaking("say", "Introduce yourself aloud: your name and where you are from."),
            ],
        },
        pattern={
            "title": "Your and my: lako and langu",
            "objective": "See how 'your name' and 'my name' differ by one word, and how thanks change for a group.",
            "activities": [
                explain("explain",
                        "Jina (name) stays the same; lako means your and langu means my. Separately, several "
                        "greetings change when you speak to more than one person.",
                        ["Jina lako ni nani? (What is your name?) → Jina langu ni … (My name is …)",
                         "Asante (Thank you, one person) → Asanteni (Thank you, more than one person)",
                         "Hujambo (Hello, one person) → Hamjambo (Hello, more than one person)"]),
                build("build-1", "Build \"My name is Amina\".", ["ni", "Amina", "Jina", "langu"],
                      ["Jina", "langu", "ni", "Amina"],
                      "Right: Jina langu ni Amina.", "Start with Jina, then my (langu)."),
                build("build-2", "Now ask someone their name.", ["nani?", "lako", "ni", "Jina"],
                      ["Jina", "lako", "ni", "nani?"],
                      "Right: Jina lako ni nani?", "Use lako (your) and end with nani? (who?)."),
            ],
        },
        story={
            "title": "Story: Amina's first class",
            "objective": "Read about Amina's first Swahili lesson, then answer a question about it.",
            "activities": [
                narration("n1", "Amina joins an evening class. The teacher welcomes everyone:"),
                turn("s1", "Teacher", "Karibu", "Welcome"),
                narration("n2", "Amina tells the class why she is there:"),
                turn("s2", "Amina", "Ninajifunza Kiswahili", "I'm learning Swahili"),
                narration("n3", "The teacher asks a long question, and Amina is lost. She says:"),
                turn("s3", "Amina", "Samahani. Sielewi", "Sorry. I don't understand"),
                narration("n4", "At the end she thanks the teacher and leaves:"),
                turn("s4", "Amina", "Asante. Kwaheri", "Thank you. Goodbye"),
                choice("check", "What did Amina say when she didn't follow the question?",
                       ["Sielewi", "Karibu", "Kwaheri"], "Sielewi",
                       "Right: Sielewi means I don't understand.", "Look at what Amina said when she was lost."),
                listening("listen", "Karibu / Ninajifunza Kiswahili / Sielewi",
                          "Welcome / I'm learning Swahili / I don't understand",
                          "Which line means 'I'm learning Swahili'?",
                          ["Sielewi", "Ninajifunza Kiswahili", "Karibu"], "Ninajifunza Kiswahili"),
            ],
        },
    ),
    unit(
        "pack.swahili.market", "sw",
        "At the market and around town",
        "Ask prices, order politely, and find your way.",
        conversation={
            "title": "Tea at the market",
            "objective": "Follow Amina buying tea and asking the way, then choose a polite refusal.",
            "activities": [
                reflection("setup", "Amina stops at a tea stall in the market."),
                turn("t1", "Vendor", "Karibu", "Welcome"),
                turn("t2", "Amina", "Naomba chai ya maziwa", "I'd like tea with milk"),
                turn("t3", "Amina", "Hii ni bei gani?", "How much is this?"),
                turn("t4", "Amina", "Asante", "Thank you"),
                turn("t5", "Vendor", "Karibu", "You're welcome"),
                turn("t6", "Amina", "Choo kiko wapi?", "Where is the toilet?"),
                turn("t7", "Vendor", "Pinda kushoto", "Turn left"),
                choice("reply", "The vendor offers a second cup but you've had enough. What do you say?",
                       ["Hapana, asante", "Naomba …", "Hii ni bei gani?"], "Hapana, asante",
                       "Right: Hapana, asante — no, thank you.", "You want to refuse politely."),
                speaking("say", "Order something you like with Naomba …, then ask the price."),
            ],
        },
        pattern={
            "title": "Turning and asking",
            "objective": "Use Pinda with a direction, and Naomba to ask for something politely.",
            "activities": [
                explain("explain",
                        "Pinda means turn; add the direction after it. Naomba (I'd like / may I have) goes before "
                        "the thing you want and sounds more polite than Ninataka (I want).",
                        ["Pinda kushoto (Turn left) / Pinda kulia (Turn right)",
                         "Naomba chai ya maziwa (I'd like tea with milk)",
                         "Naomba bili, tafadhali (The bill, please)"]),
                build("build-1", "Tell a driver to turn right.", ["kulia", "Pinda"], ["Pinda", "kulia"],
                      "Right: Pinda kulia.", "Pinda comes first."),
                build("build-2", "Ask for the bill politely.", ["tafadhali", "bili,", "Naomba"],
                      ["Naomba", "bili,", "tafadhali"],
                      "Right: Naomba bili, tafadhali.", "Start with Naomba and finish with tafadhali (please)."),
            ],
        },
        story={
            "title": "Story: Lost in the old town",
            "objective": "Follow Amina finding her way back, then answer a question.",
            "activities": [
                narration("n1", "After the market, Amina takes a wrong turn in the narrow streets. She stops someone:"),
                turn("s1", "Amina", "Samahani. Nimepotea", "Excuse me. I'm lost"),
                narration("n2", "The man points down the road:"),
                turn("s2", "Man", "Moja kwa moja", "Straight ahead"),
                narration("n3", "She finds a taxi and shows the driver the address:"),
                turn("s3", "Amina", "Nipeleke huko, tafadhali", "Take me there, please"),
                narration("n4", "Home again, she calls Juma to plan the next day:"),
                turn("s4", "Amina", "Tutaonana kesho", "See you tomorrow"),
                choice("check", "Which phrase did Amina use to say she was lost?",
                       ["Nimepotea", "Moja kwa moja", "Tutaonana kesho"], "Nimepotea",
                       "Right: Nimepotea means I'm lost.", "Look at what Amina said first."),
                listening("listen", "Nimepotea / Moja kwa moja / Tutaonana kesho",
                          "I'm lost / Straight ahead / See you tomorrow",
                          "Which line gives a direction?", ["Moja kwa moja", "Nimepotea", "Tutaonana kesho"],
                          "Moja kwa moja"),
            ],
        },
    ),
    # ------------------------------------------------------------------ Dholuo
    unit(
        "pack.dholuo.family", "luo",
        "Greetings and family",
        "Greet an elder through the day and name the people in a family.",
        conversation={
            "title": "Morning at Dayo's",
            "objective": "Follow Akinyi visiting her grandmother, then choose the reply to 'How are you?'.",
            "activities": [
                reflection("setup", "Akinyi visits her grandmother (dayo) early in the morning."),
                turn("t1", "Akinyi", "Oyawore", "Good morning"),
                turn("t2", "Dayo", "Oyawore. Idhi nade?", "Good morning. How are you?"),
                turn("t3", "Akinyi", "Adhi maber", "I'm fine"),
                turn("t4", "Akinyi", "Erokamano", "Thank you"),
                turn("t5", "Dayo", "Oriti", "Goodbye"),
                turn("t6", "Akinyi", "Wanenre machiegni", "See you soon"),
                choice("reply", "A neighbour asks you \"Idhi nade?\". Which reply fits?",
                       ["Adhi maber", "Oriti", "Oimore"], "Adhi maber",
                       "Right: Adhi maber — I'm fine.", "Idhi nade? asks how you are."),
                speaking("say", "Greet an elder aloud for the morning, then say goodbye."),
            ],
        },
        pattern={
            "title": "Going well: idhi and adhi",
            "objective": "See how the greeting and its reply mirror each other, then build both.",
            "activities": [
                explain("explain",
                        "The greeting asks how you are going: idhi (you go), and the reply answers adhi (I go). "
                        "Maber means well, and also closes the good-night wish. This note covers these phrases only.",
                        ["Idhi nade? (How are you? — how are you going?) → Adhi maber (I'm fine — I go well)",
                         "Nindi maber (Sleep well)"]),
                build("build-1", "Reply that you are fine.", ["maber", "Adhi"], ["Adhi", "maber"],
                      "Right: Adhi maber.", "Start with the I-form, Adhi."),
                build("build-2", "Wish someone a good night.", ["maber", "Nindi"], ["Nindi", "maber"],
                      "Right: Nindi maber.", "Maber (well) comes last."),
            ],
        },
        story={
            "title": "Story: The family at supper",
            "objective": "Read about Akinyi's family, then answer a question about who is who.",
            "activities": [
                narration("n1", "In the evening Akinyi's whole family eats together. Her grandfather is called kwaro:"),
                turn("s1", "Akinyi", "Kwaro", "Grandfather"),
                narration("n2", "Her grandmother, who greeted her that morning, is dayo:"),
                turn("s2", "Akinyi", "Dayo", "Grandmother"),
                narration("n3", "Her little brother's child sits on her lap. A small child is:"),
                turn("s3", "Akinyi", "Nyathi", "Child"),
                narration("n4", "When it is late she says goodnight to everyone:"),
                turn("s4", "Akinyi", "Oimore. Nindi maber", "Good evening. Sleep well"),
                choice("check", "What does Akinyi call her grandmother?", ["Dayo", "Kwaro", "Nyathi"], "Dayo",
                       "Right: dayo is grandmother.", "Kwaro is grandfather and nyathi is a child."),
                listening("listen", "Kwaro / Dayo / Nyathi", "Grandfather / Grandmother / Child",
                          "Which word means child?", ["Kwaro", "Nyathi", "Dayo"], "Nyathi"),
            ],
        },
    ),
    unit(
        "pack.dholuo.market", "luo",
        "Names, numbers, and the market",
        "Introduce yourself, count to five, and shop for food.",
        conversation={
            "title": "A visitor in Kisumu",
            "objective": "Follow Otieno meeting a visitor, then pick the reply to a name question.",
            "activities": [
                reflection("setup", "Otieno meets a visitor outside his home in Kisumu."),
                turn("t1", "Visitor", "Amosi", "Hello"),
                turn("t2", "Otieno", "Amosi", "Hello"),
                turn("t3", "Visitor", "Nyingi ng'a?", "What's your name?"),
                turn("t4", "Otieno", "Nyinga Otieno", "My name is Otieno"),
                turn("t5", "Visitor", "To ia kanye?", "Where are you from?"),
                turn("t6", "Otieno", "Aa ki Kisumu", "I'm from Kisumu"),
                turn("t7", "Visitor", "Amor kuom rado kodi", "Nice to meet you"),
                choice("reply", "Someone asks you \"Nyingi ng'a?\". Which reply fits?",
                       ["Nyinga …", "Aa ki …", "Ok ang'eyo"], "Nyinga …",
                       "Right: Nyinga … — my name is …", "Nyingi ng'a? asks for your name."),
                speaking("say", "Introduce yourself aloud with Nyinga … and Aa ki …"),
            ],
        },
        pattern={
            "title": "Counting one to five",
            "objective": "Put the numbers in order, and see how 'your name' becomes 'my name'.",
            "activities": [
                explain("explain",
                        "Counting from one to five: achiel, ariyo, adek, ang'wen, abich. Ten is apar. Separately, "
                        "nyingi (your name) and nyinga (my name) differ only in their ending.",
                        ["Achiel, ariyo, adek, ang'wen, abich (1–5)",
                         "Nyingi ng'a? (What's your name?) → Nyinga … (My name is …)"]),
                build("build-1", "Put one to five in order.", ["Adek", "Achiel", "Abich", "Ariyo", "Ang'wen"],
                      ["Achiel", "Ariyo", "Adek", "Ang'wen", "Abich"],
                      "Right: achiel, ariyo, adek, ang'wen, abich.", "Start with achiel (one)."),
                build("build-2", "Ask someone their name.", ["ng'a?", "Nyingi"], ["Nyingi", "ng'a?"],
                      "Right: Nyingi ng'a?", "Nyingi (your name) comes first."),
            ],
        },
        story={
            "title": "Story: Supper from the market",
            "objective": "Follow Otieno shopping for supper, then answer a question.",
            "activities": [
                narration("n1", "Otieno is hungry on his way home. He tells his friend:"),
                turn("s1", "Otieno", "Adwaro chiemo", "I want to eat"),
                narration("n2", "At the market he checks his pocket and smiles:"),
                turn("s2", "Otieno", "An gi pesa", "I have money"),
                narration("n3", "He buys fish from the lake and some bananas:"),
                turn("s3", "Otieno", "Rech. Rabolo", "Fish. Banana"),
                narration("n4", "He thanks the seller on the way out:"),
                turn("s4", "Otieno", "Erokamano", "Thank you"),
                choice("check", "What did Otieno buy?", ["Rech and rabolo", "Bando and alot", "Makati and tong'"],
                       "Rech and rabolo", "Right: fish and bananas.", "Look again at what he bought."),
                listening("listen", "Adwaro chiemo / An gi pesa / Erokamano",
                          "I want to eat / I have money / Thank you",
                          "Which line means 'I have money'?", ["An gi pesa", "Adwaro chiemo", "Erokamano"],
                          "An gi pesa"),
            ],
        },
    ),
    # ------------------------------------------------------------------ Amharic
    unit(
        "pack.amharic.meeting", "am",
        "Greetings and introductions",
        "Greet someone, ask their name the right way for a man or a woman, and say how much Amharic you know.",
        conversation={
            "title": "Hanna meets Dawit",
            "objective": "Follow Hanna and Dawit meeting at a friend's house, then choose the right name question.",
            "activities": [
                reflection("setup", "Hanna meets Dawit for the first time. Amharic questions change depending on whether you speak to a man or a woman."),
                turn("t1", "Hanna", "Selam (ሰላም)", "Hello"),
                turn("t2", "Dawit", "Tena yisTilliñ, indemin adderu", "Good morning, how are you?"),
                turn("t3", "Hanna", "Dehna, igziyabher yimmesgen", "Very well, thank you"),
                turn("t4", "Hanna", "Simih man new? (ስምህ ማን ነው?)", "What is your name? (to a man)"),
                turn("t5", "Dawit", "Sime Dawit new (ስሜ ዳዊት ነው)", "My name is Dawit"),
                turn("t6", "Hanna", "Siletewawekin des bilognal", "Pleased to meet you"),
                choice("reply", "Now Dawit asks Hanna her name. Which question does he use?",
                       ["Simish man new?", "Simih man new?", "Sint new?"], "Simish man new?",
                       "Right: Simish is the form used to a woman.",
                       "Simih is used to a man, and Sint new? asks a price."),
                speaking("say", "Say your own name aloud with Sime … new."),
            ],
        },
        pattern={
            "title": "Speaking to a man or a woman",
            "objective": "Notice the ending that changes for a man or a woman, then build both questions.",
            "activities": [
                explain("explain",
                        "In these phrases the word for your changes with the person you speak to: simih (your name, "
                        "to a man) and simish (your name, to a woman). Sime is my name.",
                        ["Simih man new? (What is your name? — to a man)",
                         "Simish man new? (What is your name? — to a woman)",
                         "Sime … new (My name is …)"]),
                build("build-1", "Ask a woman her name.", ["new?", "Simish", "man"], ["Simish", "man", "new?"],
                      "Right: Simish man new?", "Start with Simish (your name, to a woman)."),
                build("build-2", "Say \"My name is Hanna\".", ["Hanna", "new", "Sime"], ["Sime", "Hanna", "new"],
                      "Right: Sime Hanna new.", "Sime first, the name in the middle, new at the end."),
            ],
        },
        story={
            "title": "Story: Dawit's first lesson",
            "objective": "Read about Dawit practising his Amharic, then answer a question.",
            "activities": [
                narration("n1", "Dawit's colleague is surprised to hear him greet her in Amharic. She asks:"),
                turn("s1", "Colleague", "Amariñña yawKallu?", "Do you know Amharic?"),
                turn("s2", "Dawit", "Tinniš awKallehu", "I know a little"),
                narration("n2", "She answers quickly and he misses it:"),
                turn("s3", "Dawit", "Minalu? Algebagnim", "What did you say? I don't understand"),
                narration("n3", "She laughs, says it again slowly, and he thanks her before leaving:"),
                turn("s4", "Dawit", "Amesegnalehu. Chaw", "Thank you. Bye"),
                choice("check", "How much Amharic does Dawit say he knows?",
                       ["Tinniš awKallehu (a little)", "Yellem, alawKim (none)", "Awo, awKallehu (yes, I know it)"],
                       "Tinniš awKallehu (a little)", "Right: he knows a little.", "Look at his first answer."),
                listening("listen", "Tinniš awKallehu / Minalu? / Amesegnalehu",
                          "I know a little / What did you say? / Thank you",
                          "Which line means 'Thank you'?", ["Minalu?", "Amesegnalehu", "Tinniš awKallehu"],
                          "Amesegnalehu"),
            ],
        },
    ),
    unit(
        "pack.amharic.cafe", "am",
        "Coffee, food, and directions",
        "Order in a café, say what you don't eat, and follow simple directions.",
        conversation={
            "title": "At the buna bet",
            "objective": "Follow Hanna ordering coffee and asking the way, then choose how to decline meat.",
            "activities": [
                reflection("setup", "Hanna stops at a small café (buna bet) in Addis Ababa."),
                turn("t1", "Waiter", "Selam", "Hello"),
                turn("t2", "Hanna", "Buna be wetet", "Coffee with milk"),
                turn("t3", "Hanna", "Sint new?", "How much is it?"),
                turn("t4", "Hanna", "Amesegnalehu", "Thank you"),
                turn("t5", "Hanna", "Ibákkiwo. Yet?", "Excuse me. Where?"),
                turn("t6", "Waiter", "Kirb new. BesteKeññiwo new", "It's nearby. It's on your right"),
                choice("reply", "The waiter recommends doro wet (chicken stew), but you don't eat meat. What do you say?",
                       ["Siga albelam", "Melkam migib", "Sint new?"], "Siga albelam",
                       "Right: Siga albelam — I don't eat meat.",
                       "Melkam migib wishes someone a good meal, and Sint new? asks a price."),
                speaking("say", "Order a coffee aloud, then ask how much it is."),
            ],
        },
        pattern={
            "title": "It is …: sentences ending in new",
            "objective": "Notice how many short answers end in new (it is), then build two.",
            "activities": [
                explain("explain",
                        "New at the end of these phrases works like 'it is': put the description first. The same "
                        "word ends the price question and your name.",
                        ["Kirb new (It's nearby) / RuK new (It's far)",
                         "Fitlefit new (It's in front of you)",
                         "Sint new? (How much is it?)"]),
                build("build-1", "Say that it's far.", ["new", "RuK"], ["RuK", "new"],
                      "Right: RuK new.", "The description comes first, new last."),
                build("build-2", "Order coffee with milk.", ["wetet", "Buna", "be"], ["Buna", "be", "wetet"],
                      "Right: Buna be wetet.", "Start with buna (coffee)."),
            ],
        },
        story={
            "title": "Story: Finding the café",
            "objective": "Follow Dawit looking for the café, then answer a question.",
            "activities": [
                narration("n1", "Dawit is meeting Hanna for coffee but cannot find the café. He asks a shopkeeper:"),
                turn("s1", "Dawit", "Ibákkiwo. Yet?", "Excuse me. Where?"),
                turn("s2", "Shopkeeper", "Wedefit yihidunná, wedegrá yizuru", "Go straight ahead and turn"),
                turn("s3", "Shopkeeper", "Kirb new", "It's nearby"),
                narration("n2", "He finds Hanna already eating bread with her coffee. He says:"),
                turn("s4", "Dawit", "Melkam migib", "Enjoy your meal"),
                choice("check", "Is the café far from the shop?", ["No — Kirb new", "Yes — RuK new", "It's behind him"],
                       "No — Kirb new", "Right: it's nearby.", "Look at the shopkeeper's last line."),
                listening("listen", "Yet? / Kirb new / Melkam migib", "Where? / It's nearby / Enjoy your meal",
                          "Which line means 'It's nearby'?", ["Yet?", "Melkam migib", "Kirb new"], "Kirb new"),
            ],
        },
    ),
]


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for u in UNITS:
        result = build_manifest(
            pack_id=u["pack_id"],
            version=1,
            language=u["language"],
            title=u["title"],
            objective=u["objective"],
            publication_status="development",
            phrases=[],
            lessons=u["lessons"],
            assets=[],
            credits=u["credits"],
        )
        path = OUTPUT_DIR / (u["pack_id"] + ".json")
        path.write_bytes(result.manifest_bytes)
        print("Wrote", path.name, "bytes=" + str(len(result.manifest_bytes)))


if __name__ == "__main__":
    main()
