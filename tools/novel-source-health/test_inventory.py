import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('inventory',Path(__file__).with_name('inventory.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class InventoryContracts(unittest.TestCase):
    def test_exact_metadata_origins_only(self):
        self.assertTrue(m.approved('https://api.mknov.com/v1/getWorks'))
        for url in ['http://api.mknov.com/v1/getWorks','https://user@api.mknov.com/v1/getWorks',
                    'https://api.mknov.com/v1/getChapterBatch','https://seanovel.org/novels/book/chapters/1']:
            self.assertFalse(m.approved(url))
    def test_ids_are_positive_integral_source_ids(self):
        self.assertEqual(m.identity(m.Decimal('96.0')),'96')
        for value in [True,0,-1,m.Decimal('1.5'),'nan']:
            with self.assertRaises(m.InventoryError):m.identity(value)
    def test_duplicate_pages_are_not_reported_complete(self):
        class Client:
            audit=[]
            def get(self,url,body=None):
                if body is None:return [{'slug':'control','title_ar':'Control'}]
                return {'status':'success','total':101,'data':[{'id':i,'name':'Public work'} for i in (range(1,101) if body['offset']==0 else [1])]}
        report=m.observe(Client());state=report['sources']['novel.mknov']
        self.assertEqual(state['uniqueWorks'],100)
        self.assertEqual(state['classification'],'INCOMPLETE_OR_MUTATING_SNAPSHOT')
        self.assertEqual(report['chapterBodiesFetched'],0)
    def test_no_live_permission_is_implied_by_an_empty_report(self):
        with self.assertRaises(m.InventoryError):m.observe(None,21)

    def test_redirects_are_rejected_without_following(self):
        with self.assertRaises(m.InventoryError):m.NoRedirect().redirect_request(None,None,None,None,None,None)
    def test_access_failure_is_not_retried_or_reported_healthy(self):
        class Opener:
            def open(self,*args,**kwargs):raise m.error.HTTPError('https://api.mknov.com/v1/getWorks',429,'rate',None,None)
        client=m.Client(Opener())
        with self.assertRaisesRegex(m.InventoryError,'UPSTREAM_ACCESS_OR_RATE_LIMIT'):
            client.get('https://api.mknov.com/v1/getWorks',{})
        self.assertEqual(len(client.audit),1)
    def test_timeout_remains_inconclusive(self):
        class Opener:
            def open(self,*args,**kwargs):raise TimeoutError()
        with self.assertRaisesRegex(m.InventoryError,'NETWORK_OR_JSON_FAILURE'):
            m.Client(Opener()).get('https://api.mknov.com/v1/getWorks',{})
    def test_oversized_response_is_rejected_before_parsing(self):
        from email.message import Message
        class Response:
            status=200
            headers=Message()
            headers['Content-Type']='application/json'
            headers['Content-Length']=str(m.LIMIT+1)
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,*args):raise AssertionError('Must not read oversized body')
        class Opener:
            def open(self,*args,**kwargs):return Response()
        with self.assertRaisesRegex(m.InventoryError,'OVERSIZED_OR_INVALID_LENGTH'):
            m.Client(Opener()).get('https://api.mknov.com/v1/getWorks',{})
if __name__=='__main__':unittest.main()
