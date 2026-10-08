#!/usr/bin/env python3
"""Offline, reproducible rank relics. Licensed motifs + original Mangaro metalwork.

Requires librsvg and ImageMagick. No network, no achievement asset modifications.
"""
import hashlib
import html
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
from generate_sigil_art import gem, path, star

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "artwork/ranks"
OUT = ROOT / "app/src/main/res/drawable-nodpi"
PALETTES = [
    ("#a7a0b8", "#eee7ff", "#4b435f"),
    ("#c38a62", "#ffe3bf", "#64432e"),
    ("#63b89c", "#d0fff0", "#24544a"),
    ("#6f9fe8", "#e0edff", "#304879"),
    ("#a67be8", "#eee0ff", "#4c296e"),
    ("#d6b56d", "#fff0c6", "#795626"),
    ("#ae86f3", "#f3e0ff", "#482b74"),
]
# Seven different silhouettes: pendant, astrolabe, scrying reliquary, sword
# shield, horned dragon crest, winged seal, and celestial imperial cartouche.
FRAMES = [
    "M256 48L331 123L352 248L324 345L256 464L188 345L160 248L181 123Z",
    "M256 28L290 110L350 82L378 142L458 180L410 256L458 332L378 370L350 430L290 402L256 484L222 402L162 430L134 370L54 332L102 256L54 180L134 142L162 82L222 110Z",
    "M256 58Q370 60 402 180Q417 281 358 344L336 402L367 433L347 464L165 464L145 433L176 402L154 344Q95 281 110 180Q142 60 256 58Z",
    "M256 24L294 70L368 80L412 142L398 301Q365 405 256 483Q147 405 114 301L100 142L144 80L218 70Z",
    "M256 31L293 93L351 71L431 30L405 135L458 191L418 289L379 383L256 481L133 383L94 289L54 191L107 135L81 30L161 71L219 93Z",
    "M256 25L309 95L408 59L444 104L420 160L476 211L414 273L388 356L339 411L256 485L173 411L124 356L98 273L36 211L92 160L68 104L104 59L203 95Z",
    "M256 22L284 68L337 38L350 99Q428 71 433 151L464 192L433 235Q467 285 417 330L397 400L325 422L301 461L256 489L211 461L187 422L115 400L95 330Q45 285 79 235L48 192L79 151Q84 71 162 99L175 38L228 68Z",
]


def illustrate(asset):
    tier = asset["tier"]
    accent, pale, dark = PALETTES[tier]
    frame = FRAMES[tier]
    source = ART / "sources" / (asset["resource"].removeprefix("rank_").replace("_", "-") + ".svg")
    raw = source.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == asset["sourceSha256"]
    motif = [p.attrib["d"] for p in ET.fromstring(raw).iter("{http://www.w3.org/2000/svg}path")
             if p.attrib.get("d") and p.attrib["d"] != "M0 0h512v512H0z"]
    assert motif
    metal = "#f0eaf7" if tier < 3 else "#fff0bd"
    mid = "#89909f" if tier == 0 else "#b98b56"
    definitions = f'''<defs>
      <linearGradient id="gold" x2=".9" y2="1"><stop stop-color="{metal}"/><stop offset=".19" stop-color="{mid}"/><stop offset=".4" stop-color="#fff1cf"/><stop offset=".57" stop-color="#866537"/><stop offset=".8" stop-color="#ebc889"/><stop offset="1" stop-color="#937049"/></linearGradient>
      <linearGradient id="metal" x1=".1" x2=".85" y2="1"><stop stop-color="{metal}"/><stop offset=".21" stop-color="#646878"/><stop offset=".4" stop-color="{metal}"/><stop offset=".48" stop-color="{mid}"/><stop offset=".51" stop-color="{metal}"/><stop offset=".75" stop-color="#574d51"/><stop offset="1" stop-color="{mid}"/></linearGradient>
      <linearGradient id="gem" x2=".8" y2="1"><stop stop-color="{pale}"/><stop offset=".22" stop-color="{accent}"/><stop offset=".63" stop-color="{dark}"/><stop offset="1" stop-color="{accent}"/></linearGradient>
      <radialGradient id="enamel" cx=".34" cy=".27" r=".88"><stop stop-color="{dark}"/><stop offset=".48" stop-color="#171723"/><stop offset="1" stop-color="#080a12"/></radialGradient>
      <radialGradient id="halo"><stop stop-color="{accent}" stop-opacity=".21"/><stop offset=".6" stop-color="{accent}" stop-opacity=".06"/><stop offset="1" stop-color="{accent}" stop-opacity="0"/></radialGradient>
      <filter id="shadow" x="-.3" y="-.3" width="1.6" height="1.6"><feGaussianBlur stdDeviation="4"/></filter>
    </defs>'''
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512"><title>{html.escape(asset["name"])}</title><desc>{html.escape(asset["title"])} by Lorc, CC BY 3.0. Original Mangaro relic composition and metalwork. {asset["sourceUrl"]}</desc>', definitions,
             '<circle cx="256" cy="256" r="247" fill="url(#halo)"/>',
             path(frame, "#000000", extra='transform="translate(0 6)" filter="url(#shadow)" opacity=".65"'),
             path(frame, "url(#enamel)", "#090d16", 10), path(frame, "none", "url(#gold)", 6),
             path(frame, "none", "url(#gem)", 2, 'transform="translate(256 256) scale(.95) translate(-256 -256)"'),
             path(frame, "none", "url(#gold)", 1.2, 'transform="translate(256 256) scale(.85) translate(-256 -256)" opacity=".65"')]
    # Tier-specific engraved supports: modest chains evolve into wings and
    # imperial filigree. These are original illustrations, not shared sigils.
    for side in (1, -1):
        body = []
        if tier == 0:
            for i in range(5):
                body += [f'<ellipse cx="{198+i*3}" cy="{157+i*27}" rx="5" ry="8" fill="none" stroke="url(#metal)" stroke-width="2"/>']
        elif tier == 1:
            body += [path("M123 254Q126 137 229 108M121 276Q148 368 218 395", stroke="url(#gold)", width=4)]
            for i in range(5):
                body += [path(f"M{134+i*9} {162+i*44}l10 -5l-1 8", stroke="url(#gem)", width=2)]
        elif tier == 2:
            body += [path("M157 350Q71 203 154 142Q109 231 182 294L185 367L166 405L203 425", stroke="url(#gold)", width=7),
                     path("M157 350Q126 272 148 219", stroke="url(#gem)", width=2)]
        elif tier == 3:
            for i in range(5):
                x, y = 126+i*10, 142+i*39
                body += [path(f"M{x} {y}L{x+29} {y-13}L{x+15} {y+12}L{x+1} {y+20}Z", "url(#metal)", "#181726", 2),
                         path(f"M{x+7} {y+5}L{x+21} {y-6}", stroke="url(#gem)", width=2)]
        elif tier == 4:
            body += [path("M136 344Q75 286 101 194L82 132L112 144L121 82L158 150Q167 195 147 214L176 268L160 324", "url(#metal)", "#252132", 2)]
            for i in range(5):
                body += [gem(117+i*7, 164+i*37, 5)]
        elif tier == 5:
            for i in range(6):
                x, y = 113+i*11, 130+i*36
                body += [path(f"M{x+20} {y+45}Q{x-38} {y+2} {x-23} {y-21}Q{x+5} {y+4} {x+42} {y+6}Z", "url(#gold)", "#393125", 2),
                         path(f"M{x-11} {y-9}Q{x+6} {y+17} {x+28} {y+29}", stroke=pale, width=1, extra='opacity=".65"')]
        else:
            body += [path("M151 395C77 321 64 143 159 105C118 76 94 126 115 156C70 139 130 133 117 191C81 253 172 277 128 327C122 359 140 377 169 388", stroke="url(#gold)", width=8),
                     path("M146 363C116 292 156 290 130 241C94 207 144 181 134 155", stroke="url(#gem)", width=2)]
            for i in range(5):
                body += [gem(119+i*5, 165+i*43, 7), star(104+i*5, 184+i*43, 4)]
        parts += [f'<g transform="translate({0 if side==1 else 512} 0) scale({side} 1)">', *body, '</g>']
    # No fake textual runes: intentionally abstract etched constellations.
    ring = 111 if tier < 3 else 127
    parts += [f'<circle cx="256" cy="249" r="{ring}" fill="none" stroke="url(#gem)" stroke-width="1" opacity=".35"/>']
    for i in range(8+tier*2):
        a = (i*360/(8+tier*2)-90)*math.pi/180
        x, y = 256+ring*math.cos(a), 249+ring*math.sin(a)
        parts += [path(f"M{x:.2f} {y:.2f}l{6*math.cos(a):.2f} {6*math.sin(a):.2f}", stroke=pale, width=1.2, extra='opacity=".55"')]
    size = .63 if tier not in (0, 2) else .59
    offset = 256*(1-size)
    parts += [f'<g transform="translate({offset} {offset-8}) scale({size})">',
              '<g transform="translate(2 6)">', *[path(d, "#070811", "#070811", 5) for d in motif], '</g>',
              *[path(d, "url(#metal)", "#111522", 3.7) for d in motif],
              '<g transform="translate(-1 -1)">', *[path(d, "none", pale, 1.2, 'opacity=".58"') for d in motif], '</g>', '</g>']
    details = [
        gem(256, 262, 18),
        gem(256, 245, 15)+star(216, 207, 4),
        star(246, 208, 14)+gem(256, 388, 13),
        gem(256, 319, 20)+path("M248 158L256 144L264 158", stroke=pale, width=2),
        gem(300, 223, 9)+gem(256, 395, 25),
        gem(256, 281, 25)+star(207, 208, 6)+star(309, 209, 6),
        gem(256, 239, 32)+gem(199, 263, 12)+gem(313, 263, 12)+
        path("M183 299Q256 328 329 299", stroke="url(#gold)", width=4)+
        path("M194 305L214 317L256 308L298 317L318 305", stroke="url(#gem)", width=2)+
        '<ellipse cx="256" cy="369" rx="60" ry="17" fill="none" stroke="url(#gold)" stroke-width="2"/>'+gem(256, 367, 23),
    ]
    parts += [details[tier], gem(256, 86 if tier!=0 else 115, 10+tier*1.3), gem(256, 434, 8+tier*1.1)]
    for i in range(tier+1):
        parts += [star(256+(i-tier/2)*10, 462, 2.6)]
    if tier >= 4:
        parts += [star(179, 144, 5), star(339, 353, 5)]
    return ''.join(parts)+"</svg>"


def main():
    manifest = json.loads((ART / "manifest.json").read_text())
    assert [a['tier'] for a in manifest['assets']] == list(range(7))
    (ART / "illustrations").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mangaro-ranks-") as temp:
        for asset in manifest['assets']:
            svg = ART / "illustrations" / (asset['id'] + '.svg')
            svg.write_text(illustrate(asset))
            png = Path(temp) / (asset['id'] + '.png')
            subprocess.run(['rsvg-convert', '-w', '512', '-h', '512', '-o', str(png), str(svg)], check=True)
            webp = OUT / (asset['resource'] + '.webp')
            subprocess.run(['magick', str(png), '-strip', '-quality', '88', '-define', 'webp:method=6', str(webp)], check=True)
            asset.update(illustrationSha256=hashlib.sha256(svg.read_bytes()).hexdigest(), webpSha256=hashlib.sha256(webp.read_bytes()).hexdigest(), webpBytes=webp.stat().st_size, size=512)
    (ART / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    print('Rendered 7 distinct rank relics:',sum(a['webpBytes'] for a in manifest['assets']),'bytes')


if __name__ == '__main__':
    main()
