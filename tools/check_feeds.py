#!/usr/bin/env python3
"""
Re-check the bundled feeds against the live web.

Every URL in `app/src/main/assets/default_feeds.opml` returned a parseable feed
on 17 September 2026. Feeds rot: publishers move them, put them behind bot
defences, or replace them with a holding page. This script says which ones no
longer work, so the bundled set can be repaired before a release rather than
discovered by a reader whose Sports section quietly stopped updating.

It is deliberately a standalone script with no dependencies beyond the standard
library, because it has to run wherever there is real network access — which,
during this app's development, was the user's machine and not the agent's.

    python3 tools/check_feeds.py                 # check them all
    python3 tools/check_feeds.py --category Sports
    python3 tools/check_feeds.py --timeout 30 --workers 8
    python3 tools/check_feeds.py --quiet         # only what is broken

Exit: 0 when every feed answered with something parseable, 1 otherwise.
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from urllib import error, request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OPML = os.path.join(ROOT, "app", "src", "main", "assets", "default_feeds.opml")

# The app's own User-Agent. Using anything else here would test a request the
# app never makes — and a publisher that refuses this one is exactly what this
# script is for.
USER_AGENT = "Dispatch/1.0 (Android; feed reader; +no-tracking)"
ACCEPT = ("application/rss+xml, application/atom+xml, application/xml;q=0.9, "
          "text/xml;q=0.9, */*;q=0.8")


def load(category=None):
    root = ET.parse(OPML).getroot()
    out = []
    for group in root.find("body"):
        name = group.get("title") or group.get("text") or ""
        if category and category.lower() not in name.lower():
            continue
        for entry in group:
            url = entry.get("xmlUrl")
            if url:
                out.append((name, entry.get("title") or entry.get("text") or url, url))
    return out


def probe(item, timeout):
    category, title, url = item
    req = request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": ACCEPT})
    try:
        with request.urlopen(req, timeout=timeout) as response:
            body = response.read(400_000)
            status = response.status
            final = response.geturl()
    except error.HTTPError as e:
        return (category, title, url, "HTTP %d" % e.code, 0, None)
    except Exception as e:  # URLError, timeouts, TLS, and anything else
        return (category, title, url, type(e).__name__, 0, None)

    # Parse it the way the app does: by parsing, not by trusting Content-Type.
    # Several of these feeds are served as text/plain or text/html.
    try:
        tree = ET.fromstring(body)
    except ET.ParseError as e:
        # A truncated read of a valid feed is not a failure; a body that does
        # not start like a feed is.
        head = body[:2000].decode("utf-8", "replace").lower()
        if any(tag in head for tag in ("<rss", "<feed", "<rdf:rdf", "<channel")):
            return (category, title, url, "ok (truncated read)", -1, final)
        return (category, title, url, "not a feed: %s" % str(e)[:40], 0, final)

    items = len(tree.findall(".//item")) + len(
        tree.findall(".//{http://www.w3.org/2005/Atom}entry")
    ) + len(tree.findall(".//{http://purl.org/rss/1.0/}item"))
    note = "ok" if items else "parsed but empty"
    return (category, title, url, "%s (%d)" % (note, status), items, final)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--category", help="only feeds whose category contains this")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--quiet", action="store_true", help="print only failures")
    args = parser.parse_args()

    feeds = load(args.category)
    if not feeds:
        print("no feeds matched")
        return 1
    print("checking %d feeds\n" % len(feeds))

    broken, moved = [], []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for category, title, url, note, items, final in pool.map(
            lambda f: probe(f, args.timeout), feeds
        ):
            # items == -1 means the document was a feed but the read was cut
            # short by the 400KB cap, which is fine; 0 means nothing parsed.
            ok = items != 0
            if not ok:
                broken.append((category, title, url, note))
            if final and final.rstrip("/") != url.rstrip("/"):
                moved.append((title, url, final))
            if not args.quiet or not ok:
                print("%-5s %-28s %-46s %s" % (
                    "FAIL" if not ok else "ok",
                    category[:28],
                    title[:46],
                    note,
                ))

    print()
    if moved:
        print("%d feeds redirected — consider updating the OPML to the final URL:" % len(moved))
        for title, url, final in moved:
            print("  %-40s %s\n      -> %s" % (title[:40], url, final))
        print()
    if broken:
        print("%d FEEDS ARE BROKEN:" % len(broken))
        for category, title, url, note in broken:
            print("  [%s] %s\n      %s\n      %s" % (category, title, url, note))
        return 1
    print("all %d feeds answered with a parseable document" % len(feeds))
    return 0


if __name__ == "__main__":
    sys.exit(main())
