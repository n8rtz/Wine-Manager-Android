import gzip
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest

import numpy as np

p=pathlib.Path(__file__).with_name('run.py')
s=importlib.util.spec_from_file_location('experiment',p);e=importlib.util.module_from_spec(s);s.loader.exec_module(e)

class ExperimentTests(unittest.TestCase):
    def test_tokenizer_dp_markers_and_feed(self):
        model={'model':{'type':'Unigram','unk_id':0,'vocab':[['<unk>',0],['▁',-2],['a',-1],['b',-1],['▁a',-1],['▁ab',-0.1]]},
               'pre_tokenizer':{'type':'Metaspace','replacement':'▁'},
               'added_tokens':[{'id':10,'content':'<<ENT>>'},{'id':11,'content':'<<SEP>>'},{'id':12,'content':'[CLS]'},{'id':13,'content':'[SEP]'}]}
        with tempfile.TemporaryDirectory() as d:
            path=pathlib.Path(d)/'tokenizer.json';path.write_text(json.dumps(model));t=e.Tokenizer(path)
            self.assertEqual(t.word('ab'),[5]);self.assertEqual(t.word('z'),[0]);self.assertEqual(t.word('<<ENT>>'),[10])
            inputs=[type('Input',(),{'name':name,'type':'tensor(int64)'}) for name in ('input_ids','attention_mask','words_mask','text_lengths','span_idx','span_mask')]
            w,width,feed=e.make_feed([('AB','ab')],['ab'],t,{'ent_token':'<<ENT>>','sep_token':'<<SEP>>','max_width':2},inputs)
            self.assertEqual(feed['input_ids'].tolist(),[[12,10,5,11,5,13]])
            self.assertEqual(feed['words_mask'].tolist(),[[0,0,0,0,1,0]])
            self.assertEqual(feed['span_idx'].tolist(),[[[0,0],[0,1]]]);self.assertEqual(feed['span_mask'].tolist(),[[1,0]])

    def test_overlap_and_fail_closed(self):
        logits=np.full((1,2,2,2),-20,dtype=np.float32);logits[0,0,0,0]=4;logits[0,0,0,1]=3;logits[0,1,0,1]=2
        words=[('alpha','alpha'),('beta','beta')]
        self.assertEqual([x[0] for x in e.decode_logits(logits,words,2,['a','b'],.5,False)],[0,1])
        self.assertEqual([x[0] for x in e.decode_logits(logits,words,2,['a','b'],.5,True)],[0,1,1])
        with self.assertRaisesRegex(ValueError,'Unexpected graph output'): e.decode_logits(logits,words,2,['a'],.5,False)

    def test_direct_override_is_confidence_based_and_uses_control_fallback(self):
        control={1:{f:'original' for f in e.FIELDS}}
        candidates={1:[(0,.8,'Estate'),(0,.9,'Best Estate'),(3,.65,'France'),(5,.95,'2020')]}
        actual=e.apply_direct(control,candidates,.7)[1]
        self.assertEqual(actual['producer'],'Best Estate')
        self.assertEqual(actual['country'],'original')
        self.assertEqual(actual['vintage'],'2020')
        self.assertEqual(control[1]['producer'],'original')

    def test_frozen_device_control_with_actual_kotlin(self):
        host=os.environ.get('GLINER_HOST');java=os.environ.get('JAVA_HOME')
        if not host or not java: self.skipTest('Set GLINER_HOST and JAVA_HOME for Kotlin parity')
        with gzip.open(e.EVIDENCE,'rt') as f: rows=json.load(f)['images']
        docs=e.prepare_documents(rows,pathlib.Path(host)/'documents.jar',java+'/bin/java')
        self.assertEqual([(r['review_number'],len(docs[r['review_number']])) for r in rows],[(r['review_number'],r['words']) for r in rows])
        hints={r['review_number']:[(h['lane'],h['score'],h['text']) for h in r['lane_hints']] for r in rows}
        predictions=e.run_policy(rows,hints,pathlib.Path(host)/'decision.jar',java+'/bin/java')
        self.assertEqual([(r['review_number'],predictions[r['review_number']]) for r in rows],[(r['review_number'],r['fields']) for r in rows])
        with gzip.open(e.REFERENCES,'rt') as f: refs={r['image']:r['fields'] for r in json.load(f)['images']}
        for row in rows:
            for f in e.FIELDS:
                actual=e.outcome(refs[row['image']][f],row['fields'].get(f,''))
                recorded=row['outcomes'][f]
                if actual=='unscored': self.assertTrue(recorded.startswith('unscored'))
                else: self.assertEqual(actual,recorded)
        scores=e.score(rows,predictions,refs,predictions)
        self.assertEqual(scores['labels']['eligible'],188);self.assertEqual(scores['labels']['complete'],148)
        self.assertEqual(sum(x['correct'] for x in scores['fields'].values()),1063)

if __name__=='__main__':unittest.main()
