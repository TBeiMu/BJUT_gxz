"""Reuse the user's original bird and Octicons paths; no generated replacement artwork."""
from pathlib import Path
from PIL import Image, ImageOps
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'GongXiaoZhu/Resources/Assets.xcassets'
def catalog(folder, images):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'Contents.json').write_text(json.dumps({'images': images, 'info': {'author': 'xcode', 'version': 1}}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def prepare():
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / 'Contents.json').write_text('{"info":{"author":"xcode","version":1}}\n', encoding='utf-8')
    icon = ASSETS / 'AppIcon.appiconset'
    catalog(icon, [{'filename': 'AppIcon.png', 'idiom': 'universal', 'platform': 'ios', 'size': '1024x1024'}])
    image = Image.open(ROOT.parent / 'docs/assets/logo-bird-source.png').convert('RGB').resize((1024, 1024), Image.Resampling.LANCZOS)
    image.save(icon / 'AppIcon.png')
    bird = ASSETS / 'Bird.imageset'
    catalog(bird, [{'filename': 'Bird.png', 'idiom': 'universal'}])
    foreground = ImageOps.contain(Image.open(ROOT.parent / 'docs/assets/logo-bird-foreground.png').convert('RGBA'), (512, 512), Image.Resampling.LANCZOS)
    square = Image.new('RGBA', (512, 512), (0, 0, 0, 0))
    square.alpha_composite(foreground, ((512 - foreground.width) // 2, (512 - foreground.height) // 2))
    square.save(bird / 'Bird.png')
    mark = ASSETS / 'LaunchMark.imageset'
    catalog(mark, [{'filename': 'LaunchMark.svg', 'idiom': 'universal'}])
    (mark / 'LaunchMark.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108"><rect x="33" y="27" width="42" height="11.5" rx="4" fill="#1B1D22"/><rect x="48" y="41.5" width="12" height="25" rx="4" fill="#E5482E"/><rect x="27" y="69.5" width="54" height="11.5" rx="4" fill="#1B1D22"/></svg>\n', encoding='utf-8')
    vector = ET.parse(ROOT.parent / 'app/src/main/res/drawable/ic_github.xml').getroot()
    ns = '{http://schemas.android.com/apk/res/android}'
    width, height = vector.attrib[ns+'viewportWidth'], vector.attrib[ns+'viewportHeight']
    paths = ''.join('<path fill="#000" d="' + element.attrib[ns+'pathData'] + '"/>' for element in vector.findall('path'))
    github = ASSETS / 'GitHub.imageset'
    catalog(github, [{'filename': 'GitHub.svg', 'idiom': 'universal'}])
    (github / 'GitHub.svg').write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">{paths}</svg>\n', encoding='utf-8')
    licenses = ROOT / 'GongXiaoZhu/Resources/Licenses.txt'
    licenses.write_text((ROOT.parent / 'app/src/main/assets/licenses/octicons.txt').read_text(encoding='utf-8') + '\nSwiftSoup: MIT, https://github.com/scinfu/SwiftSoup\nSwiftNIO / SwiftNIO Transport Services: Apache-2.0, https://github.com/apple/swift-nio\n界面参考矿小助 1.4.10，Apache-2.0；iOS 业务独立实现。\n', encoding='utf-8')
    with licenses.open('a', encoding='utf-8') as handle:
        for source in sorted((ROOT / 'licenses').glob('*.txt')):
            handle.write('\n\n' + source.read_text(encoding='utf-8').replace('\\n\\n', '\n\n'))
    print('Reused bird, original mark and GitHub assets')

if __name__ == '__main__': prepare()
