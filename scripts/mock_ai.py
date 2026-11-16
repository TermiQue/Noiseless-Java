#!/usr/bin/env python3
"""Explicit test fixture. Never forwards requests to a real model."""
import json, re, time, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def article(kind='reading'):
    data={'format':'noiseless-1','type':kind,'title':'A Quiet Place to Read','subtitle':'安静阅读的力量','paragraphs':[{'en':'A good book opens a new world. Reading gives us time to think.','zh':'一本好书打开一个新世界。阅读让我们有时间思考。'}],'dictionary':{'book':{'pos':'n.','meaning':'书','context':'供阅读的书籍','ipa':'bʊk'}},'phrases':[{'en':'open a new world','zh':'打开一个新世界'}]}
    q={'number':1,'en':'What can a good book do?','zh':'一本好书可以做什么？','answer':'A','explanation':'原文给出直接依据。','evidence':'A good book opens a new world.','answerSource':'provided','explanationSource':'provided'}
    if kind in ('reading','listening'):
        q.update(type='choice',options=[{'key':'A','en':'Open a new world.','zh':'打开新世界'},{'key':'B','en':'Close a door.','zh':'关门'}])
    elif kind=='matching': data['paragraphs'][0]['label']='A'; q['type']='matching'
    elif kind=='cloze': data['wordBank']=[{'key':'A','en':'world','zh':'世界'},{'key':'B','en':'door','zh':'门'}]; q['type']='cloze'
    else: q.update(type=kind,answer='A good book opens a new world.'); data['paragraphs'][0]['en']='' if kind=='translation' else data['paragraphs'][0]['en']
    data['questions']=[q]
    return data

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        data=json.dumps({'service':'noiseless-mock-ai','mock':True,'data':[{'id':'mock-noiseless'}]}).encode(); self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        size=int(self.headers.get('Content-Length','0'))
        if size>20000000: self.send_error(413); return
        body=json.loads(self.rfile.read(size)); messages=body.get('messages',[]); system=str(messages[0].get('content','')) if messages else ''; user=str(messages[-1].get('content','')) if messages else ''
        if body.get('tools') and ('修改' in user or '改为' in user):
            arguments=json.dumps({'paragraphs':[{'index':1,'zh':'这是模拟模型工具修改后的译文。'}]},ensure_ascii=False)
            self.send_response(200); self.send_header('Content-Type','text/event-stream; charset=utf-8'); self.end_headers()
            try:
                for start in range(0,len(arguments),9):
                    function={'arguments':arguments[start:start+9]}
                    call={'index':0,'function':function}
                    if start==0: call.update(id='mock_update',type='function'); function['name']='update_article'
                    self.wfile.write(('data: '+json.dumps({'choices':[{'delta':{'tool_calls':[call]}}]},ensure_ascii=False)+'\n\n').encode()); self.wfile.flush(); time.sleep(.005)
                self.wfile.write(b'data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}\n\ndata: [DONE]\n\n'); self.wfile.flush()
            except (BrokenPipeError,ConnectionResetError): pass
            return
        if '"translation"' in system:
            text=json.dumps({'translation':'书','entry':{'headword':'book','phonetics':{'uk':'bʊk','us':'bʊk'},'senses':[{'pos':'n.','translations':['书']}],'tags':[],'collocations':[],'morphemes':[],'relatedWords':[]}},ensure_ascii=False)
        elif 'Noiseless 精读 JSON' in system:
            match=re.search(r'type=([a-z]+)',system); text=json.dumps(article(match.group(1) if match else 'reading'),ensure_ascii=False)
        elif '按用户明确要求修改' in system:
            try: data=json.loads(system.split('\n',1)[1])
            except Exception: data=article()
            data['paragraphs'][0]['zh']='这是模拟模型修改后的译文。'; text=json.dumps(data,ensure_ascii=False)
        else:
            text='这是服务器上的**模拟模型测试回答**，用于验证流式传输与文章上下文。\n\n| 结构 | 说明 |\n| --- | --- |\n| 主语 | A good book |\n| 谓语 | opens |\n| 宾语 | a new world |\n\n- 原文与译文已关联。\n- 此回复没有调用付费模型。'
        self.send_response(200); self.send_header('Content-Type','text/event-stream; charset=utf-8'); self.end_headers()
        delay=.12 if 'SLOW_TEST' in user else .005
        try:
            for start in range(0,len(text),12):
                chunk={'choices':[{'delta':{'content':text[start:start+12]}}]}; self.wfile.write(('data: '+json.dumps(chunk,ensure_ascii=False)+'\n\n').encode()); self.wfile.flush(); time.sleep(delay)
            self.wfile.write(b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'); self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError): pass

if __name__=='__main__': ThreadingHTTPServer(('127.0.0.1',int(os.environ.get('MOCK_PORT','18089'))),Handler).serve_forever()
