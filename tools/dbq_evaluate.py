#!/usr/bin/env python3
"""DBQ geometric replay evaluator. No physical/UI/latency or acceptance claims.
JSONL GT: clipId, frameId, group, eligible, quad (4 [x,y] in original pixels).
JSONL predictions: clipId, frameId, quad (or null). Missing predictions are failures.
"""
import argparse, json, math, pathlib, hashlib


def cross(a,b,c):
    return (b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0])


def validate(q):
    if not isinstance(q,list) or len(q)!=4: raise ValueError('expected four ordered points')
    if any(not isinstance(p,list) or len(p)!=2 or any(not isinstance(v,(float,int)) or isinstance(v,bool) or not math.isfinite(v) for v in p) for p in q):
        raise ValueError('non-finite or malformed points')
    turns=[cross(q[i],q[(i+1)%4],q[(i+2)%4]) for i in range(4)]
    if not(all(t>1e-9 for t in turns) or all(t < -1e-9 for t in turns)):
        raise ValueError('non-convex, degenerate or self-intersecting quad')
    return q


def signed_area(p):
    return sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(p,p[1:]+p[:1]))/2 if len(p)>2 else 0


def intersection(subject,clip):
    output=subject[:]
    orientation=1 if signed_area(clip)>0 else -1
    for a,b in zip(clip,clip[1:]+clip[:1]):
        old=output; output=[]
        if not old: break
        def distance(p): return orientation*((b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0]))
        previous=old[-1]; dp=distance(previous)
        for point in old:
            dc=distance(point)
            if (dc>=0)!=(dp>=0):
                t=dp/(dp-dc)
                output.append([previous[0]+t*(point[0]-previous[0]),previous[1]+t*(point[1]-previous[1])])
            if dc>=0: output.append(point)
            previous=point; dp=dc
    return output


def geometry(predicted,ground_truth):
    p=validate(predicted); g=validate(ground_truth)
    ai=abs(signed_area(intersection(p,g))); union=abs(signed_area(p))+abs(signed_area(g))-ai
    if union<=0: raise ValueError('zero union')
    diagonal=max(math.dist(g[0],g[2]),math.dist(g[1],g[3]))
    # Reverse winding to match GT; validate() already rejects crossed vertices.
    if signed_area(p)*signed_area(g)<0: p=list(reversed(p))
    errors=[max(math.dist(p[(i+shift)%4],g[i]) for i in range(4)) for shift in range(4)]
    error=min(errors)/diagonal
    shift=errors.index(min(errors)); aligned=[p[(i+shift)%4] for i in range(4)]
    # Mean absolute normal distance of corresponding side endpoints, divided by GT short side.
    short=min(math.dist(g[i],g[(i+1)%4]) for i in range(4))
    side=[]
    for i in range(4):
        a=g[i]; b=g[(i+1)%4]; length=math.dist(a,b)
        side.append(sum(abs((b[0]-a[0])*(v[1]-a[1])-(b[1]-a[1])*(v[0]-a[0]))/length for v in (aligned[i],aligned[(i+1)%4]))/2/short)
    return {'iou':ai/union,'cornerError':error,'maxSideError':max(side)}


def load(path):
    values={}
    for line in pathlib.Path(path).read_text().splitlines():
        if not line.strip(): continue
        row=json.loads(line); key=(row['clipId'],row['frameId'])
        if key in values: raise ValueError('duplicate frame key: '+str(key))
        values[key]=row
    return values


def evaluate(gt,predictions):
    groups={}; frames=[]
    for key,row in gt.items():
        if not isinstance(row.get('eligible'),bool): raise ValueError('GT eligibility must be explicit')
        group=groups.setdefault(row['group'],{'eligible':0,'success':0,'missing':0,'invalid':0,'excluded':0,'clips':set()})
        if not row['eligible']: group['excluded']+=1; continue
        validate(row['quad']); group['eligible']+=1; group['clips'].add(row['clipId'])
        predicted=predictions.get(key,{}).get('quad')
        reason=None; metric=None
        if predicted is None: reason='missing'; group['missing']+=1
        else:
            try: metric=geometry(predicted,row['quad'])
            except ValueError: reason='invalid'; group['invalid']+=1
        success=metric is not None and metric['iou']>=0.97 and metric['cornerError']<=0.01
        group['success']+=int(success)
        frames.append({'clipId':key[0],'frameId':key[1],'success':success,'reason':reason,'geometry':metric})
    for group in groups.values():
        group['clips']=len(group['clips'])
        group['successRate']=group['success']/group['eligible'] if group['eligible'] else None
        group['acceptance']='INCONCLUSIVE' # no frozen independent corpus / CI / physical evidence
    return {'schemaVersion':1,'scope':'original-pixel geometry replay only','groups':groups,'frames':frames,
        'extraPredictions':len(set(predictions)-set(gt)),
        'physicalAcceptance':'NOT_RUN','confirmedPrecision':None,'displayedJitter':None,'captureToPresent':None}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gt',required=True); parser.add_argument('--predictions',required=True); parser.add_argument('--output',required=True)
    args=parser.parse_args(); result=evaluate(load(args.gt),load(args.predictions))
    result['inputSha256']={name:hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest() for name,path in [('gt',args.gt),('predictions',args.predictions)]}
    pathlib.Path(args.output).write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')

if __name__=='__main__': main()
