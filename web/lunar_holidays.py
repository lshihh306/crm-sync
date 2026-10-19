import ephem, math, datetime as dt, json, sys
KST=dt.timedelta(hours=9)
def kst_date(ed): return (ephem.Date(ed).datetime()+KST).date()
def sunlon(d):  # d: datetime UTC
    s=ephem.Sun(); s.compute(ephem.Date(d), epoch=ephem.Date(d))
    return math.degrees(ephem.Ecliptic(s, epoch=ephem.Date(d)).lon)
def term_days(y0,y1):
    # KST dates on which sun crosses a multiple of 30 deg (major terms)
    out={}
    d=dt.date(y0,1,1)
    prev=sunlon(dt.datetime(d.year,d.month,d.day)-KST)
    while d<=dt.date(y1,12,31):
        n=d+dt.timedelta(days=1)
        cur=sunlon(dt.datetime(n.year,n.month,n.day)-KST)
        if int(prev//30)!=int(cur//30): out[d]=int(cur//30)*30%360
        prev=cur; d=n
    return out
def new_moons(y0,y1):
    out=[]; e=ephem.Date(dt.datetime(y0,1,1))
    while True:
        e=ephem.next_new_moon(e); k=kst_date(e)
        if k.year>y1: break
        out.append(k); e=ephem.Date(e+1)
    return out
Y0,Y1=int(sys.argv[1]),int(sys.argv[2])
terms=term_days(Y0-2,Y1+1); nms=new_moons(Y0-2,Y1+1)
ws={d.year:d for d,l in terms.items() if l==270}
res={}
for Y in range(Y0,Y1+1):
    a=ws[Y-1]; b=ws[Y]
    # month 11 starts: last new moon on or before solstice
    m11a=max(n for n in nms if n<=a); m11b=max(n for n in nms if n<=b)
    months=[n for n in nms if m11a<=n<=m11b]  # includes both M11 starts
    starts=months[:-1]
    leap=None
    if len(starts)==13:
        for i,s in enumerate(starts):
            e=months[i+1]
            if not any(s<=d<e and l%30==0 for d,l in terms.items()):
                leap=i; break
    num=[]; m=11
    for i,s in enumerate(starts):
        if i==leap: num.append(None); continue
        num.append(m); m=m%12+1
    # month 1 and 8 may be in this sequence (1 always, 8 always since seq covers M11..M10)
    seol=starts[num.index(1)]; ch=starts[num.index(8)]+dt.timedelta(days=14)
    res[Y]=(seol.isoformat(), ch.isoformat())
print(json.dumps(res))
