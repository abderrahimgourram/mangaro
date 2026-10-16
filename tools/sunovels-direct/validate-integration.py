"""Static integration guardrails and Debug APK inspection. No Android runtime execution."""
from pathlib import Path
import gzip,hashlib,json,zipfile
root=Path(__file__).resolve().parents[2];base=root/'app/src/main/java/eu/kanade/tachiyomi/novels'
repo=(base/'NovelRepository.kt').read_text();source=(base/'SunovelsSource.kt').read_text();build=(root/'app/build.gradle.kts').read_text();ui=(root/'app/src/main/java/eu/kanade/presentation/novels/NovelScreens.kt').read_text()
assert 'if (!SunovelsSource.directEnabled && SunovelsGatewayConfig.enabled)' in repo
assert 'internal val directEnabled get() = diagnosticsEnabled || eu.kanade.tachiyomi.BuildConfig.SUNOVELS_PRIVATE_ACCEPTANCE' in source
assert 'buildConfigField("boolean", "SUNOVELS_RUNTIME_AUDIT", "false")' in build
for line in ['buildConfigField("boolean", "SUNOVELS_GATEWAY_ENABLED", "false")','buildConfigField("boolean", "SUNOVELS_GATEWAY_REVIEWED", "false")']:assert line in build
assert 'repository.completeIndex(edition, refresh = restartSunIndex, maxPages = 2' in ui
assert 'restartSunIndex = false; retryEditionId' in ui
assert 'source(novel.sourceId).chapter(' in repo and 'disk.read(novel.id, chapter.id)' in repo
assert 'prepareReaderChapter(novel, chapter, manualRetry = retryRequested)' in (root/'app/src/main/java/eu/kanade/presentation/novels/SunovelsReaderEntry.kt').read_text()
assert 'sourceFingerprint' in (base/'NovelChapterIndexer.kt').read_text()
assert 'SunovelsIndexSnapshots.stale(it)' in repo
assert '!staleSun' in repo and 'SunovelsIndexSnapshots.fresh(novel.id, key)' in repo
assert 'SunovelsIndexSnapshots.visible(old, index)' in repo
assert 'checkpoint.indexParserVersion' in (base/'NovelChapterIndexer.kt').read_text()
assert 'val indexParserVersion: Int = 0' in (base/'NovelModels.kt').read_text()
assert 'const val VERSION = 3' in (base/'SunovelsIndexSnapshots.kt').read_text()
assert 'manualRetry = true' in (root/'app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt').read_text()
assert 'Text(it.failureTag()' in ui

queue=(base/'NovelDownloadQueue.kt').read_text();disk=(base/'NovelDownloadDisk.kt').read_text()
assert 'val lanes = List(2)' in queue and 'activeSources' in queue
assert 'prepared.commit' in queue and "state='DONE'" in queue
assert 'stored.checksum == novelDigest' in disk and 'StandardCopyOption.ATOMIC_MOVE' in disk
assert not any(word in source for word in ['workers.dev','vercel.app','supabase','D1','SunovelsGateway'])
assert 'if (!SunovelsAccess.durableEvidence(chapter)) return' in repo
assert 'val resolved = direct.resolvePublicChapter' in repo
assert 'if (epoch < (sunReaderEpochs[key] ?: 0)) return' in repo
assert 'overlaySunReaderEvidence(index)' in repo
assert 'SunovelsChapterDiagnostic(novel, chapter, seaReport = seaDiagnostic)' in (root/'app/src/main/java/eu/kanade/presentation/novels/NovelReaderScreen.kt').read_text()
assert 'SunovelsSource.diagnosticsEnabled || novel.sourceId !in setOf' in ui
assert 'sourceVerified && it.code == "SUN_LOCKED"' in repo
assert 'val readerFlights = SunovelsChapterFlights<SunovelsResolvedChapter>()' in source
receipt=json.loads((root/'app/src/debug/assets/novels/sunovels-catalog-v1.receipt.json').read_text())
asset=(root/'app/src/debug/assets/novels/sunovels-catalog-v1.json.gzip').read_bytes()
assert hashlib.sha256(asset).hexdigest()==receipt['gzipSha256'] and hashlib.sha256(gzip.decompress(asset)).hexdigest()==receipt['sha256']
assert not (root/'app/src/main/assets/novels/sunovels-catalog-v1.json.gzip').exists()
apk=root/'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
with zipfile.ZipFile(apk) as z:
 assert z.read('assets/novels/sunovels-catalog-v1.json.gzip')==asset
 dex = b''.join(z.read(name) for name in z.namelist() if name.endswith('.dex'))
 for marker in ['SunovelsResolutionReport','SunovelsResolvedChapter','SunovelsChapterFlights','تشخيص الفصل','Copy Diagnostic Report','REQUEST_PENDING','SEA_READER_TRACE_V1','sea-reader-two-block-v3','sourceAcceptedBlocks=','repositoryAcceptedBlocks=']:
  assert marker.encode('utf-8') in dex, marker
 abi={x.split('/')[1] for x in z.namelist() if x.startswith('lib/')};assert abi=={'arm64-v8a'},abi
meta=json.loads((apk.parent/'output-metadata.json').read_text());assert meta['applicationId'].endswith('.dev')
print(json.dumps({'staticIntegration':'PASS','apk':str(apk),'apkBytes':apk.stat().st_size,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'applicationId':meta['applicationId'],'androidRuntimeTested':False},indent=2))
