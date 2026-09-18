#!/usr/bin/env python3
"""
Render the adaptive icon headlessly, at launcher sizes and under the masks the
system actually applies, so the mark can be judged rather than imagined.

Converts the Android VectorDrawable's pathData to SVG (the path grammar is the
same) and rasterises with Chromium.

Usage: python3 tools/render_icon.py [outdir]
"""
import os, re, sys, xml.etree.ElementTree as ET
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
A = "{http://schemas.android.com/apk/res/android}"

def css_colour(value):
    """
    Android writes #AARRGGBB; CSS reads #RRGGBBAA.

    Without this, an 8-digit Android colour renders with its alpha as the red
    channel — the first preview of this icon came out pink, which is not a
    colour anywhere in the app.
    """
    if not value or not value.startswith("#"):
        return value
    digits = value[1:]
    if len(digits) == 8:
        return "#" + digits[2:] + digits[:2]
    return value


def vector_to_svg(path, bg=None, size=432):
    root = ET.parse(path).getroot()
    vw = float(root.get(A + "viewportWidth")); vh = float(root.get(A + "viewportHeight"))
    parts = []
    if bg:
        parts.append('<rect width="%g" height="%g" fill="%s"/>' % (vw, vh, bg))
    for p in root.findall("path"):
        d = p.get(A + "pathData")
        fill = p.get(A + "fillColor") or "none"
        stroke = p.get(A + "strokeColor")
        sw = p.get(A + "strokeWidth")
        cap = p.get(A + "strokeLineCap") or "butt"
        join = p.get(A + "strokeLineJoin") or "miter"
        if fill in ("#00000000", None): fill = "none"
        fill = css_colour(fill); stroke = css_colour(stroke)
        attrs = 'd="%s" fill="%s"' % (d, fill)
        if stroke:
            attrs += ' stroke="%s" stroke-width="%s" stroke-linecap="%s" stroke-linejoin="%s"' % (
                stroke, sw or 1, cap, join)
        parts.append("<path %s/>" % attrs)
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
            'viewBox="0 0 %g %g">%s</svg>' % (size, size, vw, vh, "".join(parts)))

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "icon")
    os.makedirs(out, exist_ok=True)
    fg = os.path.join(RES, "drawable", "ic_launcher_foreground.xml")
    mono = os.path.join(RES, "drawable", "ic_launcher_monochrome.xml")

    colors = ET.parse(os.path.join(RES, "values", "colors.xml")).getroot()
    bg = next(c.text for c in colors.findall("color")
              if c.get("name") == "ic_launcher_background")

    # The adaptive-icon contract: the 108dp canvas is cropped to the middle
    # 72dp, and the guaranteed-visible area is the middle 66dp.
    masks = {
        "circle": '<circle cx="50%" cy="50%" r="33.33%"/>',
        "squircle": '<rect x="16.67%" y="16.67%" width="66.66%" height="66.66%" rx="20%"/>',
        "square": '<rect x="16.67%" y="16.67%" width="66.66%" height="66.66%"/>',
    }

    # A CSS border-radius on an overflow-hidden box is a faithful enough stand-in
    # for the launcher's own mask, and needs no SVG clip plumbing.
    tiles = [(name, vector_to_svg(fg, bg=bg, size=216), shape)
             for name, shape in masks.items()]

    html = ['<html><body style="margin:0;background:#efefef;font:12px system-ui">'
            '<div id="row" style="display:inline-flex;gap:16px;padding:16px;'
            'align-items:flex-end">']
    for name, svg, shape in tiles:
        radius = {"circle": "50%", "squircle": "22%", "square": "0"}[name]
        html.append(
            '<div><div style="width:144px;height:144px;overflow:hidden;'
            'border-radius:%s"><div style="width:216px;height:216px;margin:-36px">%s</div>'
            '</div><div style="text-align:center;padding-top:6px">%s</div></div>'
            % (radius, svg, name))
    for size in (48, 72, 96):
        svg = vector_to_svg(fg, bg=bg, size=int(size * 1.5))
        html.append(
            '<div><div style="width:%dpx;height:%dpx;overflow:hidden;border-radius:50%%">'
            '<div style="width:%dpx;height:%dpx;margin:-%dpx">%s</div></div>'
            '<div style="text-align:center;padding-top:6px">%ddp</div></div>'
            % (size, size, int(size * 1.5), int(size * 1.5), int(size * 0.25), svg, size))
    svg_mono = vector_to_svg(mono, bg="#dcdcdc", size=216)
    html.append('<div><div style="width:144px;height:144px;overflow:hidden;'
                'border-radius:50%"><div style="width:216px;height:216px;'
                'margin:-36px">' + svg_mono + '</div></div>'
                '<div style="text-align:center;padding-top:6px">monochrome</div></div>')
    html.append("</div></body></html>")

    page = os.path.join(out, "icon.html")
    open(page, "w", encoding="utf-8").write("".join(html))

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch()
        p = b.new_context(device_scale_factor=2).new_page()
        p.goto("file://" + os.path.abspath(page))
        p.wait_for_timeout(200)
        p.locator("#row").screenshot(path=os.path.join(out, "icon-preview.png"))
        b.close()
    print("wrote %s/icon-preview.png" % out)

if __name__ == "__main__":
    main()
