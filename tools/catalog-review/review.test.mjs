import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {validate,decision} from "./review.mjs";
const loaded=JSON.parse(readFileSync(new URL("../../app/src/main/res/raw/catalog_reviewed_links.json",import.meta.url),"utf8"));
const fixture=()=>JSON.parse(JSON.stringify(loaded));

test("bundled maintainer decision validates and preserves exact 64-bit source strings",()=>{
  const doc=fixture();assert.equal(validate(doc),doc);
  assert.equal(doc.records[0].editions[1].sourceId,"918460697583900080");
  doc.records[0].editions[1].sourceId=918460697583900080;
  assert.throws(()=>validate(doc));
});
test("pending candidates cannot become active without an explicit dated reviewer",()=>{
  const doc=fixture(),r=doc.records[0];r.state="PENDING";delete r.reviewer;delete r.method;delete r.reviewedAt;
  assert.doesNotThrow(()=>validate(doc));r.state="ACTIVE";assert.throws(()=>validate(doc));
});
test("approval preserves references and does not mutate the input",()=>{
  const doc=fixture(),before=JSON.stringify(doc);
  const next=decision(doc,doc.records[0].key,true,"Test maintainer","2026-10-17");
  assert.equal(JSON.stringify(doc),before);assert.deepEqual(next.records[0].editions,doc.records[0].editions);
  assert.equal(next.records[0].key,doc.records[0].key);
});
test("revocation retains source references and stable work key",()=>{
  const doc=fixture(),r=doc.records[0],next=decision(doc,r.key,false,"Test maintainer","2026-10-17");
  assert.equal(next.records[0].state,"REVOKED");assert.deepEqual(next.records[0].editions,r.editions);assert.equal(next.records[0].key,r.key);
});
test("two active work records cannot claim the same source edition",()=>{
  const doc=fixture(),other=structuredClone(doc.records[0]);other.key="f".repeat(64);doc.records.push(other);
  assert.throws(()=>validate(doc));
});
test("unsafe credential and executable links are rejected",()=>{
  for(const url of ["javascript:alert(1)","https://user:password@example.org/work","/work?%74oken=PRIVATE","/work#access_token=PRIVATE"]){
    const doc=fixture();doc.records[0].editions[0].url=url;assert.throws(()=>validate(doc));
  }
  const doc=fixture();doc.records[0].evidence=["data:text/html,unsafe"];assert.throws(()=>validate(doc));
});
test("unknown versions and duplicate root keys are rejected",()=>{
  const doc=fixture();doc.schema=2;assert.throws(()=>validate(doc));
  const duplicate=fixture();duplicate.records.push(structuredClone(duplicate.records[0]));assert.throws(()=>validate(duplicate));
});
test("a reviewer cannot approve a missing record or omit identity",()=>{
  const doc=fixture();assert.throws(()=>decision(doc,"missing",true,"Test maintainer","2026-10-17"));
  assert.throws(()=>decision(doc,doc.records[0].key,true,"","2026-10-17"));
});

test("unknown fields and numeric provider identities fail closed like the Android parser",()=>{
  const doc=fixture();doc.extra=true;assert.throws(()=>validate(doc));
  const provider=fixture();provider.records[0].editions[1].providerId=775;assert.throws(()=>validate(provider));
});
