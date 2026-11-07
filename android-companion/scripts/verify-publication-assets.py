"""Verify committed assets and source pins without building or contacting a TV."""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parent.parent
assets=root/'app/src/main/assets'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
info=json.loads((assets/'home-info.json').read_text())
assert info['versionName']=='1.7.1'
assert info['signingCertificateSha256']=='cf511447bac0b9fb79767fc8f3499328e5082e0fb41ee764984adaa2c7cc0620'
assert sha(assets/'home.apk')==info['sha256'],'Bundled Home APK changed'
for name,size,digest in [
 ('fireplace.mp4',9683738,'35a99bfd61111e05ef5941a8dfcc0cdde08f4359ee0d2ac1b0a2fe254d28727c'),
 ('crackle.m4a',1120535,'e941736970cf0074c2bcf22d323c01ab9fcbb2bcfcad5b164b25724ba8a630a8')]:
 path=assets/'fireplace'/name
 assert path.stat().st_size==size and sha(path)==digest,name
pin=json.loads((root/'upstream-reference/SOURCE.json').read_text())
assert pin['revision']=='b73b330e264107ca51a34bf84dd07095b13e517d'
for name,digest in pin['files'].items():assert sha(root/'upstream-reference'/name)==digest,name
home=json.loads((root/'upstream-home-1.7.1/SOURCE.json').read_text())
assert home['revision']==pin['revision'] and home['versionName']=='1.7.1'
for name,digest in home['files'].items():assert sha(root/'upstream-home-1.7.1'/name)==digest,name
assert len(json.loads((assets/'profiles.json').read_text()))==371
assert len(json.loads((assets/'themes.json').read_text()))==28
print('Verified Home 1.7.1 source/APK pins, official Fireplace assets, 371 profiles and 28 themes.')
