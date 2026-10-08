#!/usr/bin/env python3
"""Rebuild licensed, offline sigil artwork. Requires librsvg and ImageMagick.

No downloads: every source is pinned and hash-checked against the provenance
manifest. Original frames/ornaments and licensed motifs are kept as editable SVG.
"""
import hashlib
import html
import json
import math
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "artwork/realms-sigils"
OUT = ROOT / "app/src/main/res/drawable-nodpi"
PALETTES = [
    ("#38c5ee", "#bcf6ff", "#195f93"),
    ("#ae86f1", "#eee0ff", "#543887"),
    ("#de5269", "#ece8ee", "#632736"),
    ("#e8a5ac", "#fff0d4", "#854b60"),
    ("#d4ad58", "#fff0bf", "#70512c"),
    ("#5dd5ad", "#dcffef", "#286b58"),
]
# Six deliberately different frame silhouettes: gate, astral lozenge, blade
# shield, royal cartouche, archive clasp and laurel crest.
FRAMES = [
    "M256 30L370 86L448 198L422 358L344 432L256 474L168 432L90 358L64 198L142 86Z",
    "M256 24L292 84L364 92L384 163L458 256L384 349L364 420L292 428L256 488L220 428L148 420L128 349L54 256L128 163L148 92L220 84Z",
    "M256 26L292 64L382 98L422 158L410 298Q382 404 256 476Q130 404 102 298L90 158L130 98L220 64Z",
    "M256 28Q298 28 314 76Q366 58 396 100Q430 134 408 177Q456 190 450 242Q454 296 408 311Q433 364 390 398Q359 437 310 414Q300 466 256 480Q212 466 202 414Q153 437 122 398Q79 364 104 311Q58 296 62 242Q56 190 104 177Q82 134 116 100Q146 58 198 76Q214 28 256 28Z",
    "M156 48L356 48L400 92L424 104L436 150L436 356L404 398L356 450L256 474L156 450L108 398L76 356L76 150L88 104L112 92Z",
    "M256 30Q332 48 388 102L418 202Q448 254 406 324Q359 416 256 478Q153 416 106 324Q64 254 94 202L124 102Q180 48 256 30Z",
]
NS = "{http://www.w3.org/2000/svg}"


def path(d, fill="none", stroke="none", width=1, extra=""):
    return f'<path d="{d}" fill="{fill}" stroke="{stroke}" stroke-width="{width}" stroke-linejoin="round" stroke-linecap="round" {extra}/>'


def gem(x, y, radius, color="url(#gem)"):
    return (
        path(f"M{x} {y-radius}L{x+radius*.68} {y}L{x} {y+radius}L{x-radius*.68} {y}Z", color, "url(#gold)", 2)
        + path(f"M{x} {y-radius*.73}L{x+radius*.43} {y}L{x} {y+radius*.7}Z", "#ffffff", extra='opacity=".25"')
        + path(f"M{x-radius*.43} {y}L{x} {y-radius*.73}L{x} {y+radius*.7}Z", "#03101c", extra='opacity=".32"')
        + path(f"M{x-radius*.3} {y-radius*.08}L{x} {y-radius*.6}", stroke="#ffffff", width=1.2, extra='opacity=".8"')
    )


def star(x, y, r=5, color="#ffefd1"):
    return path(f"M{x} {y-r}Q{x+r*.15} {y-r*.15} {x+r*.55} {y}Q{x+r*.15} {y+r*.15} {x} {y+r}Q{x-r*.15} {y+r*.15} {x-r*.55} {y}Q{x-r*.15} {y-r*.15} {x} {y-r}Z", color)


def ornament(world, tier):
    """Original bilateral ornaments; low tiers restrained, legendary fuller."""
    pieces = []
    for side in (1, -1):
        body = []
        if world == 0:
            for i in range(3 + tier):
                y = 155 + i * 27
                body += [path(f"M88 {y}L116 {y-14}L109 {y+9}L81 {y+19}Z", "url(#silver)", "#08101f", 2),
                         path(f"M89 {y+3}L109 {y-7}", stroke="url(#gem)", width=3)]
        elif world == 1:
            body += [path("M100 262Q142 111 256 79", stroke="url(#gold)", width=3),
                     path("M94 282Q104 352 183 407", stroke="url(#silver)", width=2)]
            for i in range(3 + tier):
                y = 139 + i * 35
                x = 134 - math.sin(i / 6 * math.pi) * 31
                body += [star(x, y, 5 + i % 2), gem(x, y, 3)]
        elif world == 2:
            body += [path("M116 317Q44 208 106 118Q91 191 154 226Q83 182 116 317Z", "url(#silver)", "#0c0e19", 2)]
            for i in range(3 + tier):
                body += [path(f"M{102+i*7} {143+i*34}Q{149+i*4} {189+i*26} {118+i*4} {238+i*21}", stroke="url(#gem)", width=2, extra='opacity=".75"')]
        elif world == 3:
            body += [path("M125 372C56 295 80 109 183 96C125 71 89 114 94 166C74 131 123 129 117 176C79 245 156 272 125 372", stroke="url(#gold)", width=8),
                     path("M137 351C103 313 146 308 117 274C85 243 135 227 108 185", stroke="url(#rose)", width=2)]
            for i in range(2 + tier):
                y = 168 + i * 42
                body += [path(f"M106 {y}Q85 {y-14} 86 {y+5}Q91 {y+22} 106 {y}Z", "url(#rose)", "url(#gold)", 1)]
        elif world == 4:
            body += [path("M113 102L96 153L96 353L124 399L175 430", stroke="url(#gold)", width=7),
                     path("M124 110L112 158L112 346L135 384L182 409", stroke="url(#silver)", width=1.5)]
            for i in range(4 + tier):
                y = 155 + i * 28
                body += [path(f"M88 {y}L107 {y}M95 {y-5}L102 {y+7}M91 {y+6}L101 {y-6}", stroke="url(#gold)", width=1.5)]
        else:
            body += [path("M146 400Q73 279 117 135", stroke="url(#gold)", width=3)]
            for i in range(4 + tier):
                y = 163 + i * 29
                x = 105 + (i - 2) ** 2 * 1.6
                body += [path(f"M{x+6} {y+11}Q{x-29} {y+3} {x-16} {y-17}Q{x+9} {y-20} {x+6} {y+11}Z", "url(#gem)", "url(#gold)", 2),
                         path(f"M{x-14} {y-13}L{x+6} {y+11}", stroke="url(#silver)", width=1)]
        pieces.append(f'<g transform="translate({0 if side==1 else 512} 0) scale({side} 1)">{"".join(body)}</g>')
    return "".join(pieces)


def focal_details(index):
    """Small hand-authored compositional accents unique to each artifact."""
    d = [
        gem(256, 231, 23) + star(222, 196, 7) + star(296, 284, 5),
        gem(256, 298, 13) + path("M226 202L286 202", stroke="url(#gem)", width=2),
        '<ellipse cx="256" cy="247" rx="29" ry="51" fill="url(#portal)"/>' + star(251, 232, 5),
        gem(256, 244, 25) + path("M233 258L256 274L279 258", stroke="url(#gold)", width=2),
        '<ellipse cx="256" cy="240" rx="22" ry="46" fill="url(#portal)"/>' + star(256, 217, 7),
        gem(208, 206, 8) + star(276, 291, 5),
        star(256, 138, 11) + path("M219 232L229 212M286 256L296 236", stroke="url(#gem)", width=2),
        gem(256, 251, 29) + path("M227 211L235 207M275 290L285 283", stroke="url(#silver)", width=2),
        '<ellipse cx="256" cy="247" rx="19" ry="21" fill="url(#gem)" stroke="url(#gold)" stroke-width="2"/>' + '<ellipse cx="256" cy="247" rx="5" ry="14" fill="#091129"/>' + star(248, 239, 4),
        gem(256, 250, 25) + star(211, 207, 5) + star(300, 297, 6),
        path("M255 213L278 228L255 244L232 228Z", "url(#gem)", "url(#gold)", 2),
        gem(256, 249, 17) + path("M222 203L290 203M222 291L290 291", stroke="url(#gold)", width=1.5),
        gem(256, 251, 10) + star(286, 215, 4),
        path("M249 147L252 261L261 284", stroke="url(#gem)", width=2.4) + gem(256, 306, 7),
        gem(256, 238, 20) + star(256, 182, 5),
        gem(256, 251, 10) + star(256, 152, 6),
        '<circle cx="256" cy="229" r="14" fill="url(#rose)" stroke="url(#gold)" stroke-width="2"/>' + star(254, 223, 5),
        gem(256, 234, 19) + gem(218, 277, 7) + gem(294, 277, 7),
        path("M239 239L256 257L274 240L256 281Z", "url(#gem)") + star(265, 270, 5),
        gem(255, 239, 21) + '<circle cx="255" cy="239" r="31" fill="none" stroke="url(#gold)" stroke-width="1"/>',
        gem(256, 242, 31) + star(232, 216, 8),
        gem(256, 205, 18) + path("M249 240L263 240M249 256L263 256", stroke="url(#gem)", width=2),
        star(242, 205, 4) + path("M213 285L262 285M254 251L289 251", stroke="url(#gold)", width=1.5),
        gem(224, 218, 15) + star(287, 288, 6),
        gem(256, 253, 23) + path("M235 274L256 284L277 274", stroke="url(#silver)", width=1.5),
        star(270, 208, 7) + path("M223 278Q242 291 267 281", stroke="url(#gem)", width=2),
        star(289, 186, 9) + path("M230 305Q258 309 278 298", stroke="url(#gold)", width=2),
        gem(256, 247, 26) + '<circle cx="256" cy="247" r="35" fill="none" stroke="url(#silver)" stroke-width="1.5"/>',
        star(256, 230, 15) + gem(231, 286, 8) + gem(303, 216, 8),
        gem(256, 219, 31) + gem(199, 276, 13) + gem(313, 276, 13) + path("M196 297Q256 312 316 297", stroke="url(#gold)", width=3) + star(245, 192, 7),
    ]
    return d[index]


def illustrate(asset, index):
    world, tier = divmod(index, 5)
    accent, pale, dark = PALETTES[world]
    frame = FRAMES[world]
    source = (ART / "sources" / f"{asset['id']}.svg").read_bytes()
    assert hashlib.sha256(source).hexdigest() == asset["sourceSha256"], asset["id"]
    svg = ET.fromstring(source)
    # Remove only the upstream full-square background, not motif detail.
    paths = [node.attrib["d"] for node in svg.iter(NS + "path") if node.attrib.get("d") not in ("M0 0h512v512H0z",)]
    motif = "".join(path(d, "url(#metal)", "#0b101b", 4.8) for d in paths)
    relief = "".join(path(d, "none", pale, 1.4, 'opacity=".65"') for d in paths)
    symbol_scale = .59 if index != 29 else .64
    offset = 256 * (1 - symbol_scale)
    # Slightly antique metals for scrolls, silver steel for Murim, golden court.
    metal_pale = "#fff4cd" if world in (3, 4, 5) else "#f3f0ef"
    metal_mid = "#bd9150" if world in (3, 4, 5) else "#7b93a5"
    metal_dark = "#624d30" if world in (3, 4, 5) else "#3e445c"
    defs = f'''<defs>
      <linearGradient id="gold" x1="0" y1="0" x2=".85" y2="1"><stop stop-color="#f7e9b2"/><stop offset=".18" stop-color="#8f693a"/><stop offset=".38" stop-color="#e6c67b"/><stop offset=".55" stop-color="#fff4d1"/><stop offset=".7" stop-color="#957044"/><stop offset="1" stop-color="#d5ad65"/></linearGradient>
      <linearGradient id="silver" x1="0" y1="0" x2="1" y2="1"><stop stop-color="{pale}"/><stop offset=".35" stop-color="#637281"/><stop offset=".48" stop-color="#f6eddd"/><stop offset=".65" stop-color="#738a96"/><stop offset="1" stop-color="#d9d7cb"/></linearGradient>
      <linearGradient id="metal" x1=".1" y1="0" x2=".8" y2="1"><stop stop-color="{metal_pale}"/><stop offset=".18" stop-color="{metal_mid}"/><stop offset=".33" stop-color="{metal_pale}"/><stop offset=".49" stop-color="{metal_mid}"/><stop offset=".51" stop-color="{metal_pale}"/><stop offset=".74" stop-color="{metal_dark}"/><stop offset="1" stop-color="{metal_mid}"/></linearGradient>
      <linearGradient id="gem" x1="0" y1="0" x2=".85" y2="1"><stop stop-color="{pale}"/><stop offset=".27" stop-color="{accent}"/><stop offset=".64" stop-color="{dark}"/><stop offset="1" stop-color="{accent}"/></linearGradient>
      <linearGradient id="rose"><stop stop-color="#ffe4de"/><stop offset=".5" stop-color="#c27391"/><stop offset="1" stop-color="#f6bcc0"/></linearGradient>
      <radialGradient id="enamel" cx=".4" cy=".28" r=".85"><stop stop-color="{dark}"/><stop offset=".5" stop-color="#151927"/><stop offset="1" stop-color="#070b14"/></radialGradient>
      <radialGradient id="portal"><stop stop-color="{pale}"/><stop offset=".25" stop-color="{accent}"/><stop offset="1" stop-color="{dark}" stop-opacity=".3"/></radialGradient>
      <radialGradient id="halo"><stop stop-color="{accent}" stop-opacity=".24"/><stop offset=".65" stop-color="{accent}" stop-opacity=".08"/><stop offset="1" stop-color="{accent}" stop-opacity="0"/></radialGradient>
      <filter id="shadow" x="-.4" y="-.4" width="1.8" height="1.8"><feGaussianBlur stdDeviation="5"/></filter>
      <clipPath id="inner"><path d="{frame}" transform="translate(256 256) scale(.73) translate(-256 -256)"/></clipPath>
    </defs>'''
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><title>{html.escape(asset["name"])}</title><desc>Adapted from {html.escape(asset["title"])} by {asset["author"]}, CC BY 3.0. Original Mangaro metalwork, frame, inlays and composition. Source: {html.escape(asset["sourceUrl"])}</desc>', defs,
             '<circle cx="256" cy="254" r="249" fill="url(#halo)"/>',
             path(frame, "#000000", extra='transform="translate(0 7)" opacity=".6" filter="url(#shadow)"'),
             path(frame, "url(#enamel)", "#0b1019", 12),
             path(frame, "none", "url(#gold)" if world != 2 else "url(#silver)", 7),
             path(frame, "none", "url(#gem)", 2, 'transform="translate(256 256) scale(.94) translate(-256 -256)"'),
             path(frame, "url(#enamel)", "url(#silver)", 1.5, 'transform="translate(256 256) scale(.82) translate(-256 -256)"'),
             path(frame, "none", "url(#gold)", 1, 'transform="translate(256 256) scale(.78) translate(-256 -256)" opacity=".5"'),
             ornament(world, tier)]
    # Restrained etching behind the focal motif; no pseudotext or copied runes.
    parts += ['<g clip-path="url(#inner)" opacity=".23">']
    for i in range(8 + tier * 2):
        angle = (i * 360 / (8 + tier * 2) + tier * 11) * math.pi / 180
        x, y = 256 + 122 * math.cos(angle), 256 + 122 * math.sin(angle)
        parts += [path(f"M256 256L{x:.2f} {y:.2f}", stroke=accent, width=.7),
                  f'<circle cx="{x:.2f}" cy="{y:.2f}" r="2.1" fill="{pale}"/>']
    parts += ['<circle cx="256" cy="256" r="124" fill="none" stroke="url(#silver)" stroke-width="1" stroke-dasharray="3 8"/>', '</g>']
    # Milgrain edges, tier pips and original jewel clasps.
    for i in range(6):
        angle = (i * 60 + tier * 4) * math.pi / 180
        x, y = 256 + 174 * math.cos(angle), 256 + 174 * math.sin(angle)
        parts += [gem(round(x, 2), round(y, 2), 4.7 if tier < 3 else 6.2)]
    parts += [gem(256, 72, 15 + tier * 2), gem(256, 421, 10 + tier * 2),
              path("M212 412Q256 438 300 412M223 427Q256 446 289 427", stroke="url(#gold)", width=2)]
    for i in range(tier + 1):
        parts += [star(256 + (i - tier / 2) * 12, 452, 3.5)]
    parts += [f'<g transform="translate({offset:.3f} {offset-9:.3f}) scale({symbol_scale})">',
              '<g transform="translate(2 7)">', "".join(path(d, "#03060d", "#03060d", 6) for d in paths), '</g>', motif,
              '<g transform="translate(-1 -1.3)">', relief, '</g>', '</g>', focal_details(index)]
    if tier >= 3:
        parts += [star(176, 125, 4), star(344, 344, 5)]
    if index == 29:
        # Original emerald imperial crown: its own generous silhouette, not
        # another color variation of the low-tier community frame.
        parts += [
            path("M166 375Q117 327 134 266Q152 319 191 335M346 375Q395 327 378 266Q360 319 321 335", stroke="url(#gold)", width=5),
            path("M138 273Q106 249 104 209L154 241L166 285M374 273Q406 249 408 209L358 241L346 285", "url(#gold)", "#23251e", 2),
            path("M146 270L133 190L177 227L209 159L256 212L303 159L335 227L379 190L366 270L352 314Q256 343 160 314Z", "url(#metal)", "#15211d", 5),
            path("M155 269L150 226L184 249L212 188L256 235L300 188L328 249L362 226L357 269L344 303Q256 324 168 303Z", "url(#enamel)", "url(#gold)", 2),
            path("M157 281Q256 306 355 281L347 309Q256 333 165 309Z", "url(#gold)", "#433a27", 2),
            path("M178 270L191 239L212 206L227 242L213 277Z", "url(#gem)", "url(#gold)", 2),
            path("M334 270L321 239L300 206L285 242L299 277Z", "url(#gem)", "url(#gold)", 2),
            gem(256, 258, 38), gem(185, 290, 9), gem(327, 290, 9),
            gem(209, 158, 12), gem(303, 158, 12), gem(133, 190, 8), gem(379, 190, 8),
            gem(256, 398, 24), gem(173, 362, 10), gem(339, 362, 10),
            star(243, 231, 6), star(321, 176, 6),
        ]
    parts += ['</svg>']
    return "".join(parts)


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", help="Rebuild only one illustration, retaining the manifest order.")
    selected = parser.parse_args().id
    manifest = json.loads((ART / "manifest.json").read_text())
    assert len(manifest["assets"]) == 30
    if selected and selected not in {asset["id"] for asset in manifest["assets"]}:
        parser.error("Unknown achievement ID; no artwork was changed.")
    rendered = 0
    OUT.mkdir(parents=True, exist_ok=True)
    (ART / "illustrations").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mangaro-sigils-") as temp:
        for index, asset in enumerate(manifest["assets"]):
            if selected and asset["id"] != selected:
                continue
            illustration = ART / "illustrations" / f"{asset['id']}.svg"
            illustration.write_text(illustrate(asset, index))
            png = Path(temp) / f"{asset['id']}.png"
            subprocess.run(["rsvg-convert", "-w", "512", "-h", "512", "-o", str(png), str(illustration)], check=True)
            resource = OUT / f"{asset['resource']}.webp"
            subprocess.run(["magick", str(png), "-strip", "-quality", "88", "-define", "webp:method=6", str(resource)], check=True)
            asset["illustrationSha256"] = hashlib.sha256(illustration.read_bytes()).hexdigest()
            asset["webpSha256"] = hashlib.sha256(resource.read_bytes()).hexdigest()
            asset["webpBytes"] = resource.stat().st_size
            rendered += 1
    (ART / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"Rendered {rendered} distinct 512px WebPs; {sum(a['webpBytes'] for a in manifest['assets']):,} bytes total across all 30.")


if __name__ == "__main__":
    main()
