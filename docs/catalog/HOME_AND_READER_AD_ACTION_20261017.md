# Home redraw and reader ad action — private code40

The user reported an unwanted edition-count badge, Home-only stuttering and an
unresponsive reader advertising button. Preserve all prior catalog/account/source
work; no backend/schema/source/download changes.

## Home

HomeViewModel already projects verified catalog groups away from the UI thread.
HomeTab nevertheless regrouped all four sections plus featured content during
composition. Each composed DiscoveryShelf then regrouped its section again.
Remove those duplicate projections from normal Home rendering. Shelf rows take a
bounded preview of the ViewModel results. Build the combined reference list only
when those sections change. Reconcile the selected work on an explicit open/chooser
action; retain matching verified groups from other sections and all alternatives.
Remove the visible source-count badge from MangaroMangaCard; its existing
long-press source chooser remains available. Popular/Latest/Search identity
policies and user edition preferences are unchanged.

## Advertising action

The correct SmartLink URL was already present in SmartLinkConfiguration and its
existing browser Intent. No evidence establishes a missing URL on the phone.
However, ReaderAdPageActivity created a disabled button and installed its click
listener only after an unbounded workspace-readiness wait and journal read. That
is a concrete silent noninteractive path; the exact real-phone failure has not
been traced. Register the action immediately, show opening feedback, bound
workspace readiness to five seconds and offer retry on the same action. Check
original owner and the current pending gate before invoking the existing validated
browser-launch flow. A different account closes the page; errors never grant a
step. Do not change the URL, download gate, journal, browser-return policy,
privacy choices or advertising entitlement rules.

## Verification

90 selected JUnit tests passed, zero failures/errors/skips. Three new Home tests
cover alternatives across sections, missing selection and a host benchmark;
three new action tests cover immediate readiness, bounded timeout/retry and owner
isolation. Existing registry and SmartLink/page/journal contracts passed.
For 128 authored records, 15 warmed host samples (median): whole-catalog grouping
5.694409 ms; selected-work grouping 0.792068 ms. Normal Home composition no longer
invokes either operation. This is not a physical-device frame/FPS measurement or
proof of the phone's exact bottleneck. No live advertising request was made.

Manual acceptance: Home opening/scrolling is smooth; no source-count badge;
long-press still exposes both editions. At a due reader boundary, deliberately
press the advertising button; confirm the external browser opens the configured
link and returning resumes the exact pending chapter. If launch fails, report
the visible error. Android runtime/advertising acceptance remains pending.
