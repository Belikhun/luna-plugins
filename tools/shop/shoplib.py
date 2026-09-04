"""Offline reader for LunaShop's items.yml.

item-data is base64 of ItemStack.serializeAsBytes(), which is already gzipped NBT;
an item's key is the last seven hex digits of sha256 over those same bytes. A Nova
item is a minecraft:shulker_shell carrying components.minecraft:custom_data.nova.id.
"""

import re,base64,gzip,io,struct

def load(path='items.yml'):
    lines=open(path,encoding='utf-8').read().split('\n')
    i=lines.index('shop-items:')
    entries={}; order=[]; cur=None
    for ln in lines[i+1:]:
        m=re.match(r'^  ([^\s:]+):\s*$', ln)
        if m:
            cur=m.group(1).strip("'"); entries[cur]={}; order.append(cur); continue
        m=re.match(r'^    ([a-z-]+): (.*)$', ln)
        if m and cur: entries[cur][m.group(1)]=m.group(2)
    return entries, order

def nbt(data):
    b=io.BytesIO(data)
    def rd(n): return b.read(n)
    def name():
        l=struct.unpack('>H',rd(2))[0]; return rd(l).decode('utf-8')
    def payload(t):
        if t==0: return None
        if t==1: return struct.unpack('>b',rd(1))[0]
        if t==2: return struct.unpack('>h',rd(2))[0]
        if t==3: return struct.unpack('>i',rd(4))[0]
        if t==4: return struct.unpack('>q',rd(8))[0]
        if t==5: return struct.unpack('>f',rd(4))[0]
        if t==6: return struct.unpack('>d',rd(8))[0]
        if t==7:
            n=struct.unpack('>i',rd(4))[0]; return rd(n)
        if t==8: return name()
        if t==9:
            et=struct.unpack('>b',rd(1))[0]; n=struct.unpack('>i',rd(4))[0]
            return [payload(et) for _ in range(n)]
        if t==10:
            d={}
            while True:
                t2=struct.unpack('>b',rd(1))[0]
                if t2==0: return d
                k=name(); d[k]=payload(t2)
        if t==11:
            n=struct.unpack('>i',rd(4))[0]; return [struct.unpack('>i',rd(4))[0] for _ in range(n)]
        if t==12:
            n=struct.unpack('>i',rd(4))[0]; return [struct.unpack('>q',rd(8))[0] for _ in range(n)]
        raise ValueError(t)
    t=struct.unpack('>b',rd(1))[0]; name(); return payload(t)

def decode(b64):
    raw=base64.b64decode(b64)
    if raw[:2]==b'\x1f\x8b': raw=gzip.decompress(raw)
    return nbt(raw)

def describe(d):
    mid=d.get('id')
    comps=d.get('components',{}) or {}
    cd=comps.get('minecraft:custom_data')
    extra=''
    if isinstance(cd,dict):
        extra=' custom_data='+repr(cd)
    return mid+extra
