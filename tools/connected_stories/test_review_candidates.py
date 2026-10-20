import unittest
from review_candidates import review, public_url


class ReviewTest(unittest.TestCase):
    def document(self):
        novel = {'format':'TEXT_NOVEL','source':'novel.fixture','url':'https://fixture.test/n/1',
                 'publicUrl':'https://fixture.test/n/1','names':['Same Name']}
        comic = {'format':'MANHWA','source':'1','url':'/comic/1',
                 'publicUrl':'https://fixture.test/comic/1','names':['Same Name']}
        edge = {'id':'fixture','type':'ADAPTATION_OF','original':novel,'adaptation':comic,
                'publisherOriginal':'https://publisher.test/novel',
                'publisherAdaptation':'https://publisher.test/comic',
                'evidence':['https://publisher.test/explicit-adaptation']}
        return {'editions':[novel,comic],'relations':[edge]}

    def test_explicit_publisher_pair_is_pending_not_verified(self):
        result=review(self.document())
        self.assertEqual(result['pending'][0]['reason'],'READY_FOR_MANUAL_REVIEW')
        self.assertEqual(result['pending'][0]['status'],'PENDING')
        self.assertEqual(result['approvedAutomatically'],0)

    def test_same_name_never_approves_relationship(self):
        doc=self.document();doc['relations'][0].pop('evidence')
        result=review(doc)
        self.assertEqual(result['pending'][0]['reason'],'MISSING_TYPED_EVIDENCE_OR_EDITION')
        self.assertEqual(result['candidateHints'][0]['reason'],'NAME_HINT_ONLY')

    def test_missing_edition_conflicts_and_wrong_formats_fail_closed(self):
        doc=self.document();doc['editions']=doc['editions'][:1]
        self.assertEqual(review(doc)['pending'][0]['reason'],'MISSING_TYPED_EVIDENCE_OR_EDITION')
        doc=self.document();doc['relations'][0]['conflicts']=['different-publication']
        self.assertEqual(review(doc)['pending'][0]['reason'],'CONFLICT_REQUIRES_REVIEW')
        doc=self.document();doc['relations'][0]['type']='SEQUEL'
        self.assertEqual(review(doc)['pending'][0]['reason'],'MISSING_TYPED_EVIDENCE_OR_EDITION')

    def test_private_urls_and_duplicate_records_rejected(self):
        doc=self.document();doc['editions'][0]['publicUrl']='https://secret@publisher.test/n'
        with self.assertRaises(ValueError):review(doc)
        doc=self.document();doc['editions'].append(doc['editions'][0])
        with self.assertRaises(ValueError):review(doc)

    def test_meaningful_publisher_ids_are_preserved_without_accepting_credentials(self):
        url='https://series.naver.com/novel/detail.series?productNo=4130558'
        self.assertTrue(public_url(url))
        self.assertFalse(public_url(url+'&token=fixture'))
        self.assertFalse(public_url(url.replace('series.naver.com','untrusted.test')))

    def test_batch_budget_enforced(self):
        with self.assertRaises(ValueError):review({'editions':[{}]*1001,'relations':[]})


if __name__=='__main__':unittest.main()
