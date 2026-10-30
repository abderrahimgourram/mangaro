import assert from 'node:assert/strict';
import fs from 'node:fs';

const origin = 'https://mangaro-web.vercel.app';
const routes = ['/', '/features', '/download', '/about', '/privacy', '/terms', '/open-source', '/support'];
const read = file => fs.readFileSync(`dist/${file}`, 'utf8');
const release = JSON.parse(fs.readFileSync('release.json', 'utf8'));
const config = JSON.parse(fs.readFileSync('vercel.json', 'utf8'));
const titles = new Set();
const descriptions = new Set();
for (const route of routes) {
  const html = read(route === '/' ? 'index.html' : `${route.slice(1)}.html`);
  const title = html.match(/<title>(.*?)<\/title>/)?.[1];
  const meta = Object.fromEntries([...html.matchAll(/<meta (?:name|property)="([^"]+)" content="([^"]*)">/g)].map(m => [m[1], m[2]]));
  assert.ok(title && meta.description, route);
  titles.add(title); descriptions.add(meta.description);
  assert.equal(html.match(/<link rel="canonical" href="([^"]+)">/)?.[1], origin + route);
  assert.equal(meta['og:url'], origin + route);
  assert.match(meta.robots, /^index,follow/);
  assert.ok(!/noindex/i.test(html), route);
  for (const key of ['og:title', 'og:description', 'og:image', 'og:image:alt', 'twitter:title', 'twitter:description', 'twitter:image']) assert.ok(meta[key], `${route}: ${key}`);
  assert.equal([...html.matchAll(/<h1[ >]/g)].length, 1, route);
  assert.match(html, /<html lang="ar" dir="rtl">/);
  assert.match(html, /<meta name="viewport"/);
  assert.match(html, /class="skip"/);
  assert.match(html, /<noscript>/);
  for (const [, href] of html.matchAll(/<a[^>]* href="(\/[^" ]*)"/g)) {
    const target = href.split(/[?#]/)[0];
    assert.ok(routes.includes(target) || fs.existsSync(`dist${target}`), `${route}: broken link ${target}`);
  }
  for (const [img] of html.matchAll(/<img\b[^>]*>/g)) {
    assert.match(img, /\balt="[^"]*"/); assert.match(img, /\bwidth="\d+"/); assert.match(img, /\bheight="\d+"/);
  }
  const data = JSON.parse(html.match(/<script type="application\/ld\+json">(.*?)<\/script>/s)[1]);
  assert.equal(data['@context'], 'https://schema.org');
  for (const item of data['@graph']) {
    assert.ok(!item.review && !item.aggregateRating);
    if (item['@type'] === 'SoftwareApplication') {
      assert.equal(item.softwareVersion, release.versionName);
      assert.equal(item.downloadUrl, release.url);
      assert.equal(item.fileSize, `${release.bytes} bytes`);
      assert.equal(item.operatingSystem, 'Android 8.0+');
      assert.ok(read('download.html').includes('id="release-highlights"'));
    }
  }
  if (route !== '/') {
    assert.ok(config.redirects.some(r => r.source === `${route}.html` && r.destination === route && r.permanent));
    assert.ok(config.rewrites.some(r => r.source === route && r.destination === `${route}.html`));
  }
}
assert.equal(titles.size, routes.length); assert.equal(descriptions.size, routes.length);
assert.deepEqual([...read('sitemap.xml').matchAll(/<loc>(.*?)<\/loc>/g)].map(m => m[1]), routes.map(r => origin + r));
assert.equal(read('robots.txt'), `User-agent: *\nAllow: /\nDisallow: /api/\nSitemap: ${origin}/sitemap.xml\n`);
const verification = 'google3bdfade8e114f8ca.html';
assert.deepEqual(fs.readFileSync(`dist/${verification}`), fs.readFileSync(verification));
assert.equal(config.cleanUrls, false, 'Keep the exact Google .html URL reachable');
assert.ok(!config.redirects.some(r => r.source === `/${verification}`));
assert.deepEqual(fs.readFileSync('dist/app-ads.txt'), fs.readFileSync('app-ads.txt'));
assert.match(read('privacy.html'), /InMobi Choice CMP/);
assert.ok(!config.headers.some(rule => /noindex/i.test(JSON.stringify(rule)) && !rule.source.startsWith('/ad-slot')));
console.log('SEO validation passed: eight public pages, crawlable links, metadata, JSON-LD, canonical routing, sitemap, robots, verification bytes and InMobi assets.');
