#!/usr/bin/env python3
"""Generate XiaomiParts Android string resources for every supported locale.

The script uses Google Translate's unofficial JSON endpoint, which replies
with a plain array of translations. To avoid timeouts and rate limiting it:

* sends many strings in one request, as repeated ``q`` parameters, and gets
  back one translation per string (no markers to get mangled),
* batches by encoded request size (``--batch-chars``),
* sends a single request at a time by default (``--jobs 1``),
* waits between requests (``--delay``),
* retries throttling/network failures with exponential backoff,
* caches every successful translation so reruns only fetch new/changed text,
* writes the cache after every batch, so an interrupted run resumes where it
  stopped,
* reuses existing catalogs with ``--seed`` (one-time cache bootstrap).

Typical use::

    tools/generate_translations.py                  # update every locale
    tools/generate_translations.py de fr            # update only those locales
    tools/generate_translations.py --validate       # validate existing catalogs
    tools/generate_translations.py --seed           # import catalogs, then update
    tools/generate_translations.py --force          # ignore cache, retranslate all

If Google starts throttling, increase ``--delay`` (for example ``--delay 5``)
or run a few locales at a time. The cache means nothing is lost either way.
If a request fails with HTTP 400 while the batch has several strings, the
batch is split automatically. If a single string still fails (for example a
placeholder comes back mangled), that string keeps its English text and is
retried on the next run. A locale whose language code Google does not know
(``ast`` is one) is written in English with a warning.
"""

from __future__ import annotations

import argparse
import functools
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
try:
    ANDROID_ROOT = ROOT.parents[3]
except IndexError:  # Standalone checkout, not inside an AOSP tree.
    ANDROID_ROOT = ROOT

SOURCE = ROOT / "res" / "values" / "strings.xml"
CACHE_PATH = ROOT / ".translation-cache.json"
ENDPOINT = "https://clients5.google.com/translate_a/t"
CLIENT = "dict-chrome-ex"
PLACEHOLDER_RE = re.compile(r"%(\d+)\$([a-zA-Z])")
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

# Resource qualifier, translation-service language code. English variants and
# Android pseudo-locales intentionally use the default resources.
LOCALES = [
    ("af", "af"), ("am", "am"), ("ar", "ar"), ("as", "as"),
    ("az", "az"), ("be", "be"), ("bg", "bg"), ("bn", "bn"),
    ("bs", "bs"), ("ca", "ca"), ("cs", "cs"), ("da", "da"),
    ("de", "de"), ("el", "el"), ("es", "es"), ("et", "et"),
    ("eu", "eu"), ("fa", "fa"), ("fi", "fi"), ("fr", "fr"),
    ("gl", "gl"), ("gu", "gu"), ("hi", "hi"), ("hr", "hr"),
    ("hu", "hu"), ("hy", "hy"), ("in", "id"), ("is", "is"),
    ("iw", "he"), ("ja", "ja"), ("ka", "ka"), ("kk", "kk"),
    ("km", "km"), ("kn", "kn"), ("ko", "ko"), ("ky", "ky"),
    ("lo", "lo"), ("lt", "lt"), ("lv", "lv"), ("mk", "mk"),
    ("ml", "ml"), ("mn", "mn"), ("mr", "mr"), ("ms", "ms"),
    ("my", "my"), ("nb", "no"), ("ne", "ne"), ("nl", "nl"),
    ("or", "or"), ("pa", "pa"), ("pl", "pl"),
    ("pt-rBR", "pt"), ("pt-rPT", "pt"), ("ro", "ro"),
    ("ru", "ru"), ("si", "si"), ("sk", "sk"), ("sl", "sl"),
    ("sq", "sq"), ("sr", "sr"), ("b+sr+Latn", "sr-Latn"),
    ("sv", "sv"), ("sw", "sw"), ("ta", "ta"), ("te", "te"),
    ("th", "th"), ("tl", "tl"), ("tr", "tr"), ("uk", "uk"),
    ("ur", "ur"), ("uz", "uz"), ("vi", "vi"),
    ("zh-rCN", "zh-CN"), ("zh-rHK", "zh-TW"), ("zh-rTW", "zh-TW"),
    ("zu", "zu"), ("ast-rES", "ast"), ("ckb", "ckb"),
    ("gd", "gd"), ("cy", "cy"), ("fur-rIT", "fur"),
    ("nn-rNO", "no"),
]

# Catalogs in these directories are maintained by hand; the generator never
# writes them and validation never requires them to be complete.
HAND_MAINTAINED = {"it"}


class BatchFormatError(RuntimeError):
    """The service returned an unusable batch; split it and retry."""


class BatchTooLarge(BatchFormatError):
    """The request was rejected as too big; split it and retry."""


class LanguageUnsupported(RuntimeError):
    """Google Translate does not recognise the requested language code."""


class TranslationCache:
    """Persistent ``locale qualifier -> source text -> translation`` cache."""

    def __init__(self, path: Path | None):
        self.path = path
        self.data: dict[str, dict[str, str]] = {}
        self._lock = threading.Lock()
        self._save_lock = threading.Lock()
        self._dirty = False
        if path is not None and path.exists():
            try:
                raw = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    self.data = {
                        str(language): {
                            str(source): str(value)
                            for source, value in values.items()
                        }
                        for language, values in raw.items()
                        if isinstance(values, dict)
                    }
            except (OSError, ValueError) as error:
                print(
                    f"warning: ignoring unreadable cache {path}: {error}",
                    file=sys.stderr,
                )

    def get(self, language: str, source: str) -> str | None:
        with self._lock:
            return self.data.get(language, {}).get(source)

    def put_many(self, language: str, values: dict[str, str]) -> None:
        if self.path is None or not values:
            return
        with self._lock:
            self.data.setdefault(language, {}).update(values)
            self._dirty = True

    def clear(self) -> None:
        with self._lock:
            self.data.clear()
            self._dirty = True

    def save(self, force: bool = False) -> None:
        if self.path is None:
            return
        with self._save_lock:
            with self._lock:
                if not self._dirty and not force:
                    return
                payload = json.dumps(
                    self.data,
                    ensure_ascii=False,
                    indent=2,
                    sort_keys=True,
                )
                self._dirty = False
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                temporary = self.path.with_name(self.path.name + ".tmp")
                temporary.write_text(payload + "\n", encoding="utf-8")
                os.replace(temporary, self.path)
            except OSError:
                with self._lock:
                    self._dirty = True
                raise


class RateLimiter:
    """Reserve request slots globally, even when using multiple workers."""

    def __init__(self, interval: float):
        self.interval = max(0.0, interval)
        self._lock = threading.Lock()
        self._next = 0.0

    def wait(self) -> None:
        if self.interval <= 0:
            return
        with self._lock:
            now = time.monotonic()
            slot = max(now, self._next)
            self._next = slot + self.interval
        delay = slot - time.monotonic()
        if delay > 0:
            time.sleep(delay)


def source_strings() -> list[tuple[str, str]]:
    root = ET.parse(SOURCE).getroot()
    return [
        (node.attrib["name"], "".join(node.itertext()))
        for node in root.findall("string")
    ]


def protect_placeholders(text: str) -> str:
    return PLACEHOLDER_RE.sub(
        lambda match: f"__ARG{match.group(1)}{match.group(2)}__",
        text,
    )


def restore_placeholders(text: str) -> str:
    return re.sub(r"__ARG(\d+)([a-zA-Z])__", r"%\1$\2", text)


def normalize_placeholders(text: str, source: str) -> str:
    expected = PLACEHOLDER_RE.findall(source)
    normalized = text
    for number, kind in expected:
        suffix = kind.lower()
        # Some languages cause the service to drop the leading/trailing
        # underscores or even the type suffix from the protected token
        # (Friulian turns ``__ARG1s__`` into ``ARG1``). Recover it from the
        # source. The token may also come back transliterated.
        for word, transliterated in (
            ("ARG", suffix),
            ("АРГ", {"s": "с", "d": "д"}.get(suffix, suffix)),
            ("ΑΡΓ", {"s": "σ", "d": "δ"}.get(suffix, suffix)),
        ):
            normalized = re.sub(
                rf"(?<![^\W\d_])_*{word}{number}(?!\d)"
                rf"(?:[_\s]*{transliterated})?_*(?![^\W\d_])",
                f"%{number}${kind}",
                normalized,
                flags=re.IGNORECASE,
            )
    if sorted(PLACEHOLDER_RE.findall(normalized)) != sorted(expected):
        raise BatchFormatError(
            f"placeholder mismatch: source={source!r}, "
            f"translation={normalized!r}",
        )
    return normalized


def request_size(text: str) -> int:
    """Characters a string adds to the encoded request URL."""
    return len(urllib.parse.urlencode({"q": text})) + 1


def request_translations(
    language: str,
    texts: list[str],
    *,
    timeout: float,
    retries: int,
    limiter: RateLimiter,
) -> list[str]:
    """Translate ``texts`` in one request, preserving their order."""
    params = [
        ("client", CLIENT),
        ("sl", "en"),
        ("tl", language),
        *[("q", text) for text in texts],
    ]
    request = urllib.request.Request(
        ENDPOINT + "?" + urllib.parse.urlencode(params),
        headers={"User-Agent": USER_AGENT},
    )
    last_error: Exception | None = None

    for attempt in range(max(1, retries)):
        limiter.wait()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = response.read().decode("utf-8", errors="replace")
            try:
                decoded = json.loads(payload)
            except ValueError as error:
                raise RuntimeError(
                    "unexpected response from the translation service: "
                    f"{payload.strip()[:120]!r}",
                ) from error
            if not isinstance(decoded, list) or len(decoded) != len(texts):
                received = len(decoded) if isinstance(decoded, list) else "no"
                raise RuntimeError(
                    f"translation service returned {received} results for "
                    f"{len(texts)} strings",
                )
            return [str(value) for value in decoded]
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code in (400, 413, 414):
                # Usually the URL is too long; split the batch and retry.
                raise BatchTooLarge(
                    f"HTTP {error.code} for {language} "
                    f"({len(texts)} strings, {sum(map(len, texts))} chars)",
                ) from error
            if error.code in (403, 429):
                retry_after = error.headers.get("Retry-After") if error.headers else None
                try:
                    wait = float(retry_after) if retry_after else 0.0
                except ValueError:
                    wait = 0.0
                wait = max(wait, 5.0 * (attempt + 1))
            elif 500 <= error.code < 600:
                wait = min(60.0, 2.0 ** (attempt + 1))
            else:
                # Other 4xx responses will not improve by retrying.
                raise RuntimeError(
                    f"HTTP {error.code} for {language}: {error.reason}",
                ) from error
        except BatchTooLarge:
            raise
        except Exception as error:  # timeouts, DNS, TLS, malformed responses
            last_error = error
            wait = min(60.0, 2.0 ** (attempt + 1))

        time.sleep(wait + random.uniform(0.0, 0.75))

    raise RuntimeError(
        f"request failed after {max(1, retries)} attempts: {last_error}",
    )


_LANGUAGE_SUPPORT: dict[str, bool] = {}


def language_supported(
    language: str,
    *,
    timeout: float,
    retries: int,
    limiter: RateLimiter,
) -> bool:
    """Probe once per language whether the service accepts its code."""
    known = _LANGUAGE_SUPPORT.get(language)
    if known is not None:
        return known
    try:
        request_translations(
            language,
            ["Hello"],
            timeout=timeout,
            retries=retries,
            limiter=limiter,
        )
    except BatchTooLarge:
        _LANGUAGE_SUPPORT[language] = False
        return False
    _LANGUAGE_SUPPORT[language] = True
    return True


def make_batches(
    entries: list[tuple[int, str, str, str]],
    max_chars: int,
) -> list[list[tuple[int, str, str, str]]]:
    batches: list[list[tuple[int, str, str, str]]] = []
    current: list[tuple[int, str, str, str]] = []
    size = 0
    for item in entries:
        piece = request_size(item[3])
        if current and size + piece > max_chars:
            batches.append(current)
            current = []
            size = 0
        current.append(item)
        size += piece
    if current:
        batches.append(current)
    return batches


def translate_batch(
    locale: str,
    language: str,
    batch: list[tuple[int, str, str, str]],
    *,
    cache: TranslationCache,
    limiter: RateLimiter,
    timeout: float,
    retries: int,
    stats: dict[str, int],
) -> dict[int, str]:
    results: dict[int, str] = {}
    pending: list[tuple[int, str, str, str]] = []

    for index, name, source, line in batch:
        # The cache is keyed by resource qualifier, not by translation
        # language: ``nb`` and ``nn-rNO`` both use ``no`` (and ``pt-rBR`` and
        # ``pt-rPT`` both use ``pt``), but they are different catalogs.
        cached = cache.get(locale, source)
        if cached is not None:
            results[index] = cached
            stats["cached"] += 1
        else:
            pending.append((index, name, source, line))

    if not pending:
        return results

    translated = request_translations(
        language,
        [line for _, _, _, line in pending],
        timeout=timeout,
        retries=retries,
        limiter=limiter,
    )
    stats["requests"] += 1

    if len(translated) != len(pending):
        raise BatchFormatError(
            f"got {len(translated)} translations for {len(pending)} strings",
        )

    new_values: dict[str, str] = {}
    for (index, name, source, _), raw in zip(pending, translated):
        value = restore_placeholders(raw.strip())
        if not value and source.strip():
            raise BatchFormatError(f"empty translation for {source!r}")
        value = normalize_placeholders(value, source)
        results[index] = value
        new_values[source] = value

    cache.put_many(locale, new_values)
    stats["translated"] += len(new_values)
    return results


def translate_entries(
    locale: str,
    language: str,
    batch: list[tuple[int, str, str, str]],
    *,
    cache: TranslationCache,
    limiter: RateLimiter,
    timeout: float,
    retries: int,
    stats: dict[str, int],
) -> dict[int, str]:
    try:
        return translate_batch(
            locale,
            language,
            batch,
            cache=cache,
            limiter=limiter,
            timeout=timeout,
            retries=retries,
            stats=stats,
        )
    except BatchFormatError as error:
        if isinstance(error, BatchTooLarge) and len(batch) > 1:
            # A 400 on a large batch is either a long URL or an unsupported
            # language; probe once so we do not split all the way down.
            if not language_supported(
                language,
                timeout=timeout,
                retries=retries,
                limiter=limiter,
            ):
                raise LanguageUnsupported(
                    f"HTTP 400 for {language} — the language code may not be "
                    "supported by Google Translate",
                ) from error
        if len(batch) == 1:
            if isinstance(error, BatchTooLarge):
                raise LanguageUnsupported(
                    f"{error} — the language code may not be supported by "
                    "Google Translate",
                ) from error
            # Keep the English text instead of discarding the whole catalog;
            # the string is retried on the next run rather than cached.
            index, name, source, _ = batch[0]
            print(
                f"  {language}: keeping English for {name} ({error})",
                file=sys.stderr,
                flush=True,
            )
            return {index: source}
        print(
            f"  {language}: splitting {len(batch)} strings ({error})",
            file=sys.stderr,
            flush=True,
        )
        middle = len(batch) // 2
        results = translate_entries(
            locale,
            language,
            batch[:middle],
            cache=cache,
            limiter=limiter,
            timeout=timeout,
            retries=retries,
            stats=stats,
        )
        results.update(
            translate_entries(
                locale,
                language,
                batch[middle:],
                cache=cache,
                limiter=limiter,
                timeout=timeout,
                retries=retries,
                stats=stats,
            ),
        )
        return results


def xml_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "\\'")
    )


def android_unescape(value: str) -> str:
    """Undo the escaping applied by :func:`xml_escape`."""
    return value.replace("\\'", "'")


OFFICIAL_SETTINGS_STRINGS = {
    "cancel": "cancel",
    "dismiss": "dismiss",
    "screen_resolution_dialog_confirm": "apply",
    "screen_resolution_dialog_cancel": "cancel",
    "thermal_apply": "apply",
    "thermal_reset_confirm": "reset",
}


@functools.lru_cache(maxsize=None)
def official_settings_string(qualifier: str, name: str) -> str | None:
    resource_name = OFFICIAL_SETTINGS_STRINGS.get(name)
    if not resource_name:
        return None
    candidates = [
        ANDROID_ROOT / "packages/apps/Settings/res" / f"values-{qualifier}" / "strings.xml",
        ANDROID_ROOT / "vendor/crowdin/overlay/packages/apps/Settings/res" /
            f"values-{qualifier}" / "strings.xml",
    ]
    for candidate in candidates:
        if not candidate.exists():
            continue
        node = ET.parse(candidate).getroot().find(f"string[@name='{resource_name}']")
        if node is None:
            continue
        value = "".join(node.itertext()).strip()
        if len(value) >= 2 and value[0] == value[-1] == '"':
            value = value[1:-1]
        return value
    return None


def translate_locale(
    qualifier: str,
    language: str,
    entries: list[tuple[str, str]],
    args: argparse.Namespace,
    cache: TranslationCache,
    limiter: RateLimiter,
) -> str:
    stats = {"requests": 0, "cached": 0, "translated": 0}

    official: dict[int, str] = {}
    pending: list[tuple[int, str, str, str]] = []
    for index, (name, value) in enumerate(entries):
        official_value = official_settings_string(qualifier, name)
        if official_value is not None:
            official[index] = official_value
        else:
            pending.append((index, name, value, protect_placeholders(value)))

    # Translate each distinct English string only once per locale.
    representative: dict[str, tuple[int, str, str, str]] = {}
    for item in pending:
        representative.setdefault(item[2], item)

    translated_by_source: dict[str, str] = {}
    batches = make_batches(list(representative.values()), args.batch_chars)
    for number, batch in enumerate(batches, start=1):
        if args.verbose and len(batches) > 1:
            print(
                f"  {qualifier}: batch {number}/{len(batches)} "
                f"({len(batch)} strings)",
                flush=True,
            )
        try:
            results = translate_entries(
                qualifier,
                language,
                batch,
                cache=cache,
                limiter=limiter,
                timeout=args.timeout,
                retries=args.retries,
                stats=stats,
            )
        except LanguageUnsupported as error:
            # Complete the catalog in English rather than failing outright;
            # unsupported text is not cached, so it is retried next run.
            print(
                f"  {qualifier}: {error}; keeping English for the rest",
                file=sys.stderr,
                flush=True,
            )
            for item in representative.values():
                translated_by_source.setdefault(item[2], item[2])
            break
        for index, value in results.items():
            source = entries[index][1]
            translated_by_source[source] = value
        cache.save()
    translated: dict[int, str] = {}
    for index, name, source, line in pending:
        translated[index] = translated_by_source[source]

    output_dir = ROOT / "res" / f"values-{qualifier}"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / "strings.xml"
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Machine-translated baseline with official AOSP terminology where available. -->",
        "<resources>",
    ]
    for index, (name, source) in enumerate(entries):
        value = official[index] if index in official else translated[index]
        value = normalize_placeholders(value, source)
        lines.append(f'    <string name="{name}">{xml_escape(value)}</string>')
    lines.extend(["</resources>", ""])
    output.write_text("\n".join(lines), encoding="utf-8")

    return (
        f"{qualifier}: {stats['translated']} translated, "
        f"{stats['cached']} cached, {stats['requests']} requests"
    )


def seed_cache(
    cache: TranslationCache,
    locales: list[tuple[str, str]],
    entries: list[tuple[str, str]],
) -> int:
    """Import existing catalog values into the cache.

    This assumes the existing catalogs were generated from the current English
    strings. Use ``--force`` if the English text changed and you want fresh
    translations.
    """
    source_by_name = dict(entries)
    seeded = 0
    for qualifier, _ in locales:
        catalog = ROOT / "res" / f"values-{qualifier}" / "strings.xml"
        if not catalog.exists():
            continue
        try:
            nodes = ET.parse(catalog).getroot().findall("string")
        except ET.ParseError as error:
            print(
                f"warning: cannot seed {qualifier}: {error}",
                file=sys.stderr,
            )
            continue
        values: dict[str, str] = {}
        for node in nodes:
            name = node.attrib.get("name")
            source = source_by_name.get(name)
            if source is None:
                continue
            value = "".join(node.itertext())
            if value:
                values[source] = android_unescape(value)
        cache.put_many(qualifier, values)
        seeded += len(values)
    cache.save()
    return seeded


def validate_catalogs() -> None:
    entries = source_strings()
    source = dict(entries)
    failures: list[str] = []
    skipped: list[str] = []
    catalogs = sorted((ROOT / "res").glob("values-*/strings.xml"))
    for catalog in catalogs:
        qualifier = catalog.parent.name.removeprefix("values-")
        if qualifier in HAND_MAINTAINED:
            skipped.append(qualifier)
            continue
        nodes = ET.parse(catalog).getroot().findall("string")
        translated = {
            node.attrib["name"]: "".join(node.itertext())
            for node in nodes
        }
        missing = source.keys() - translated.keys()
        extra = translated.keys() - source.keys()
        placeholders = [
            name
            for name in source.keys() & translated.keys()
            if sorted(PLACEHOLDER_RE.findall(source[name])) !=
                sorted(PLACEHOLDER_RE.findall(translated[name]))
        ]
        if len(nodes) != len(entries) or missing or extra or placeholders:
            failures.append(
                f"{catalog.parent.name}: count={len(nodes)}, "
                f"missing={sorted(missing)}, extra={sorted(extra)}, "
                f"placeholders={placeholders}",
            )
    if failures:
        raise SystemExit("\n".join(failures))
    checked = len(catalogs) - len(skipped)
    note = f" (skipped hand-maintained: {', '.join(skipped)})" if skipped else ""
    print(
        f"{checked} localized catalogs validated; "
        f"{len(entries)} strings per catalog{note}",
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Generate localized string catalogs using Google Translate. "
            "Requests are batched, rate-limited, retried and cached."
        ),
    )
    parser.add_argument(
        "locales",
        nargs="*",
        metavar="QUALIFIER",
        help="only regenerate these resource qualifiers (for example: de fr)",
    )
    parser.add_argument(
        "--validate",
        action="store_true",
        help="validate existing catalogs against res/values/strings.xml",
    )
    parser.add_argument(
        "--seed",
        action="store_true",
        help="import existing catalogs into the cache before translating",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="ignore cached translations and retranslate everything",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=1,
        help="number of locales to process concurrently (default: 1)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=2.0,
        help="minimum seconds between requests, shared by all workers (default: 2)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=20.0,
        help="per-request timeout in seconds (default: 20)",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=5,
        help="attempts per request before failing (default: 5)",
    )
    parser.add_argument(
        "--batch-chars",
        type=int,
        default=3000,
        help=(
            "maximum encoded request size per batch, in characters "
            "(default: 3000)"
        ),
    )
    parser.add_argument(
        "--cache",
        type=Path,
        default=CACHE_PATH,
        help=f"translation cache path (default: {CACHE_PATH})",
    )
    parser.add_argument(
        "--no-cache",
        action="store_true",
        help="disable reading and writing the translation cache",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="print per-batch progress",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(sys.argv[1:] if argv is None else argv)

    if args.validate:
        validate_catalogs()
        return

    entries = source_strings()
    requested = set(args.locales)
    known = {qualifier for qualifier, _ in LOCALES}
    unknown = requested - known
    if unknown:
        raise SystemExit(f"unknown locale(s): {', '.join(sorted(unknown))}")

    locales = [
        (qualifier, language)
        for qualifier, language in LOCALES
        if qualifier not in HAND_MAINTAINED
        and (not requested or qualifier in requested)
    ]
    if not locales:
        hand = sorted(requested & HAND_MAINTAINED)
        if hand:
            raise SystemExit(
                f"{', '.join(hand)} is hand-maintained; edit it directly",
            )
        raise SystemExit("no matching locales")

    cache = TranslationCache(None if args.no_cache else args.cache)
    if args.force:
        cache.clear()
        if args.seed:
            print("note: --force disables --seed", file=sys.stderr)
    elif args.seed:
        seeded = seed_cache(cache, locales, entries)
        print(f"seeded {seeded} translations from existing catalogs", flush=True)
    elif not args.no_cache and not cache.data and any(
        (ROOT / "res" / f"values-{qualifier}" / "strings.xml").exists()
        for qualifier, _ in locales
    ):
        print(
            "tip: --seed reuses existing catalogs instead of retranslating; "
            "run it once if this is a fresh checkout",
            file=sys.stderr,
        )

    limiter = RateLimiter(args.delay)
    print(
        f"generating {len(locales)} locale(s): jobs={max(1, args.jobs)}, "
        f"delay={args.delay:g}s, timeout={args.timeout:g}s, "
        f"cache={cache.path or 'disabled'}",
        flush=True,
    )

    failures: list[str] = []

    def run(qualifier: str, language: str) -> str:
        return translate_locale(
            qualifier,
            language,
            entries,
            args,
            cache,
            limiter,
        )

    try:
        if max(1, args.jobs) == 1:
            for qualifier, language in locales:
                try:
                    print(f"translated {run(qualifier, language)}", flush=True)
                except Exception as error:
                    failures.append(f"{qualifier}: {error}")
                    print(f"FAILED {qualifier}: {error}", flush=True)
        else:
            with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as executor:
                futures = {
                    executor.submit(run, qualifier, language): qualifier
                    for qualifier, language in locales
                }
                for future in as_completed(futures):
                    qualifier = futures[future]
                    try:
                        print(
                            f"translated {future.result()}",
                            flush=True,
                        )
                    except Exception as error:
                        failures.append(f"{qualifier}: {error}")
                        print(f"FAILED {qualifier}: {error}", flush=True)
    except KeyboardInterrupt:
        print("\ninterrupted; saving cache...", file=sys.stderr)
        cache.save()
        raise SystemExit(130)
    finally:
        cache.save()

    if failures:
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
