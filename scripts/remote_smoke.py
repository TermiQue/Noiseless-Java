#!/usr/bin/env python3
"""Run against the SSH-forwarded test service or on the deployment host. No real AI calls."""
import argparse, base64, json, os, secrets, time, urllib.request, urllib.error
from pathlib import Path

parser=argparse.ArgumentParser(); parser.add_argument('--url',default='http://127.0.0.1:18088'); parser.add_argument('--state',required=True); parser.add_argument('--resume',action='store_true'); args=parser.parse_args()
checks=[]
def check(name,ok):
    if not ok: raise AssertionError(name)
    checks.append(name)
def api(method,path,body=None,token=None,expected=200,raw=False):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    request=urllib.request.Request(args.url+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,timeout=45) as response: code=response.status; payload=response.read()
    except urllib.error.HTTPError as e: code=e.code; payload=e.read()
    if code!=expected: raise AssertionError(f'{method} {path}: expected {expected}, got {code}: {payload[:300]!r}')
    if raw: return payload
    data=json.loads(payload); return data.get('value',data)
def wait(job,token):
    for _ in range(200):
        data=api('GET','/api/v1/ai/jobs/'+job,token=token)['data']
        if data['status'] in ('complete','failed','cancelled'): return data
        time.sleep(.06)
    raise AssertionError('AI job timeout')
def job(body,token):
    data=wait(api('POST','/api/v1/ai/jobs',body,token)['id'],token)
    check('AI '+body['kind']+' '+body.get('type',''),data['status']=='complete')
    return data['result']

state=Path(args.state)
for attempt in range(60):
    try:
        check('health',api('GET','/health')['service']=='noiseless-java'); break
    except urllib.error.URLError:
        if attempt==59: raise
        time.sleep(.5)
if args.resume:
    saved=json.loads(state.read_text()); auth=api('POST','/api/v1/auth/login',{'email':saved['email'],'password':saved['password']}); token=auth['token']
    check('account survives restart',auth['user']['id']==saved['user'])
    check('article survives restart',api('GET','/api/v1/articles/'+saved['article'],token=token)['article']['title']==saved['title'])
    check('attachment survives restart',api('GET','/api/v1/files/'+saved['attachment'],token=token,raw=True)==b'noiseless remote attachment')
    check('vocabulary survives restart',len(api('GET','/api/v1/vocabulary',token=token)['words'])==1)
    check('chat survives restart',len(api('GET','/api/v1/chats/'+saved['chat'],token=token)['data']['messages'])==2)
else:
    run=str(int(time.time())); password=secrets.token_urlsafe(24); email=f'smoke-{run}@noiseless.test'
    a=api('POST','/api/v1/auth/register',{'email':email,'password':password,'name':'Remote smoke fixture'}); token=a['token']
    b=api('POST','/api/v1/auth/register',{'email':f'isolation-{run}@noiseless.test','password':secrets.token_urlsafe(24),'name':'Isolation fixture'}); other=b['token']
    api('GET','/api/v1/library',expected=401); check('unauthenticated access rejected',True)
    article={'format':'noiseless-1','type':'reading','title':'Remote smoke '+run,'paragraphs':[{'en':'A good book opens a new world.','zh':'好书打开新世界。'}],'dictionary':{'book':{'meaning':'书','pos':'n.'}},'extension':{'retained':True}}
    doc=api('POST','/api/v1/articles',article,token); file=doc['file']; check('article save',doc['revision']==1)
    api('GET','/api/v1/articles/'+file,token=other,expected=404); api('PUT','/api/v1/articles/'+file,{'article':article,'revision':1},other,expected=404); check('cross-account article isolation',True)
    folder=api('POST','/api/v1/library/actions',{'action':'create-folder','name':'Remote test'},token)['folders'][0]['id']
    api('POST','/api/v1/library/actions',{'action':'move-article','file':file,'folder':folder},token); api('POST','/api/v1/library/actions',{'action':'update-article','file':file,'tags':['green']},token); check('folders tags and ordering',True)
    article['title']+=' updated'; updated=api('PUT','/api/v1/articles/'+file,{'article':article,'revision':1},token); api('PUT','/api/v1/articles/'+file,{'article':article,'revision':1},token,expected=409); check('revision conflict and extension preservation',updated['article']['extension']['retained'])
    check('history backup',len(api('GET','/api/v1/articles/'+file+'/history',token=token))==1)
    vocab=api('POST','/api/v1/vocabulary/add',{'file':file,'word':'Book'},token); vocab=api('POST','/api/v1/vocabulary/add',{'file':file,'word':'book'},token); check('vocabulary normalization and source deduplication',len(vocab['words'])==1 and len(vocab['words'][0]['sources'])==1)
    upload=api('POST','/api/v1/files',{'name':'remote-notes.txt','mime':'text/plain','data':base64.b64encode(b'noiseless remote attachment').decode()},token)
    api('GET','/api/v1/files/'+upload['id'],token=other,expected=404); check('attachment upload and isolation',api('GET','/api/v1/files/'+upload['id'],token=token,raw=True)==b'noiseless remote attachment')
    check('bundled offline lexicon',api('GET','/api/v1/lexicon?word=reading',token=token)['source']=='offline')
    settings=api('GET','/api/v1/ai/settings',token=token); check('explicit mock model configured',settings['models']==['mock-noiseless'])
    chat=api('POST','/api/v1/chats',{'file':file,'revision':2},token); cid=chat['id']; result=job({'kind':'chat','sessionId':cid,'text':'分析第一段'},token); check('streamed Markdown chat', '模拟模型' in result['text'])
    api('GET','/api/v1/chats/'+cid,token=other,expected=404)
    for kind in ('reading','cloze','matching','listening','translation','writing'):
        result=job({'kind':'generate','type':kind,'text':'original test material','attachmentIds':[upload['id']]},token); check('validated draft '+kind,result['article']['type']==kind)
    check('generation needs confirmation',len(api('GET','/api/v1/articles',token=token))==1)
    job({'kind':'resolve','file':file,'word':'book','context':article['paragraphs'][0]['en']},token); check('cloud lexicon write',api('GET','/api/v1/lexicon?word=book',token=token)['source']=='user')
    rewrite=job({'kind':'rewrite','file':file,'text':'改第一段译文'},token); check('rewrite stays a preview',api('GET','/api/v1/articles/'+file,token=token)['article']['paragraphs'][0]['zh']==article['paragraphs'][0]['zh'] and '模拟' in rewrite['article']['paragraphs'][0]['zh'])
    task=api('POST','/api/v1/ai/jobs',{'kind':'chat','sessionId':cid,'text':'SLOW_TEST'},token)
    for _ in range(50):
        status=api('GET','/api/v1/ai/jobs/'+task['id'],token=token)['data']
        if status.get('outputChars',0)>0: break
        time.sleep(.04)
    check('real incremental progress',status.get('outputChars',0)>0); api('POST','/api/v1/ai/jobs/'+task['id']+'/cancel',{},token); check('cancel job',wait(task['id'],token)['status']=='cancelled')
    check('cancel does not append a completed chat turn',len(api('GET','/api/v1/chats/'+cid,token=token)['data']['messages'])==2)
    tool_doc=api('POST','/api/v1/articles',article,token); tool_file=tool_doc['file']; tool_chat=api('POST','/api/v1/chats',{'file':tool_file,'revision':1},token)['id']
    receipt=job({'kind':'chat','sessionId':tool_chat,'text':'请修改第一段译文'},token); check('streamed update_article receipt',receipt['articleEdit']['revision']==2)
    changed=api('GET','/api/v1/articles/'+tool_file,token=token); check('article tool atomic save and backup','工具修改' in changed['article']['paragraphs'][0]['zh'] and len(api('GET','/api/v1/articles/'+tool_file+'/history',token=token))==1)
    api('PUT','/api/v1/articles/'+tool_file,{'article':article,'revision':2},token)
    stale=wait(api('POST','/api/v1/ai/jobs',{'kind':'chat','sessionId':tool_chat,'text':'请修改第一段译文'},token)['id'],token); check('tool rejects stale chat snapshots',stale['status']=='failed')
    api('DELETE','/api/v1/articles/'+tool_file,{'revision':3},token)
    profile=api('PUT','/api/v1/ai/profiles',{'name':'Synthetic inert profile','baseUrl':'https://api.openai.com/v1','protocol':'chat','apiKey':'mock-never-sent','models':['not-called'],'manualModel':True},token)[0]
    check('personal provider key never returned','mock-never-sent' not in json.dumps(profile) and 'encryptedKey' not in profile)
    api('DELETE','/api/v1/ai/profiles/'+profile['id'],{},other,expected=404); check('provider ownership isolation',True)
    api('DELETE','/api/v1/ai/profiles/'+profile['id'],{},token); check('provider deletion default fallback',api('GET','/api/v1/ai/settings',token=token)['defaultProfileId']=='server')
    exported=api('GET','/api/v1/export',token=token); imported=api('POST','/api/v1/import',exported,other); check('atomic cloud export/import',imported['imported']==1)
    api('DELETE','/api/v1/articles/'+file,{'revision':2},token); check('unavailable vocabulary sources retained',not api('GET','/api/v1/vocabulary',token=token)['words'][0]['sources'][0]['available'])
    restored=api('POST','/api/v1/trash/'+file+'/restore',{},token); check('trash restore revision monotonic',restored['revision']==3)
    state.parent.mkdir(parents=True,exist_ok=True); state.write_text(json.dumps({'email':email,'password':password,'user':a['user']['id'],'article':file,'title':article['title'],'attachment':upload['id'],'chat':cid})); state.chmod(0o600)
print(json.dumps({'passed':len(checks),'checks':checks,'real_ai_calls':0},ensure_ascii=False,indent=2))
