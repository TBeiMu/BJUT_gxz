"""Package an unsigned iPhoneOS .app and emit metadata from the actual bytes. No install or tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tempfile
import zipfile

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--notes', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'v\d+\.\d+\.\d+', args.tag) or not re.fullmatch(r'[a-f0-9]{40}', args.source): raise SystemExit('版本标签或源码 SHA 格式错误')
    app = Path(args.app).resolve()
    with (app / 'Info.plist').open('rb') as handle: info = plistlib.load(handle)
    if info['CFBundleShortVersionString'] != args.tag[1:] or info['CFBundleIdentifier'] != 'io.github.tbeimu.gongxiaozhu.ios' or info['MinimumOSVersion'] != '17.0' or info['CFBundleSupportedPlatforms'] != ['iPhoneOS']: raise SystemExit('iOS 包版本、系统或平台信息不符')
    if any(app.rglob('_CodeSignature')) or any(app.rglob('embedded.mobileprovision')): raise SystemExit('构建包含签名或描述文件')
    executable = app / info['CFBundleExecutable']
    architectures = subprocess.check_output(['xcrun', 'lipo', '-archs', str(executable)], text=True).split()
    if architectures != ['arm64']: raise SystemExit('iOS 可执行文件必须仅包含 arm64 架构')
    signature = subprocess.run(['codesign', '-dv', str(app)], capture_output=True, text=True)
    if signature.returncode == 0: raise SystemExit('App 已被签名，拒绝作为未签名包交付')
    output = Path(args.output).resolve(); output.mkdir(parents=True, exist_ok=True)
    ipa = output / 'gongxiaozhu-ios-unsigned.ipa'
    with tempfile.TemporaryDirectory(prefix='gxz-ipa-') as temporary:
        payload = Path(temporary) / 'Payload'; payload.mkdir()
        shutil.copytree(app, payload / '工小助.app', symlinks=True)
        # Write UTF-8 ZIP names explicitly; ditto omits the language flag for Chinese paths.
        with zipfile.ZipFile(ipa, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in [payload, *sorted(payload.rglob('*'))]:
                name = path.relative_to(payload.parent).as_posix()
                if path.is_symlink():
                    entry = zipfile.ZipInfo(name)
                    entry.create_system = 3
                    entry.external_attr = path.lstat().st_mode << 16
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, os.readlink(path).encode('utf-8'))
                else:
                    archive.write(path, name)
    with zipfile.ZipFile(ipa) as archive:
        if 'Payload/工小助.app/Info.plist' not in archive.namelist() or f'Payload/工小助.app/{info["CFBundleExecutable"]}' not in archive.namelist(): raise SystemExit('IPA Payload 结构错误')
        if archive.testzip() is not None: raise SystemExit('IPA ZIP 完整性错误')
        if not archive.getinfo(f'Payload/工小助.app/{info["CFBundleExecutable"]}').external_attr >> 16 & 0o111: raise SystemExit('IPA 主程序缺少执行权限')
    digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
    (output / 'SHA256SUMS-ios.txt').write_text(f'{digest}  {ipa.name}\n', encoding='utf-8')
    metadata = {'platform': 'ios', 'versionName': info['CFBundleShortVersionString'], 'versionCode': int(info['CFBundleVersion']), 'minSystemVersion': info['MinimumOSVersion'], 'ipaURL': f'https://github.com/TBeiMu/BJUT_gxz/releases/download/{args.tag}/{ipa.name}', 'ipaSize': ipa.stat().st_size, 'ipaSHA256': digest, 'releaseTag': args.tag, 'releaseNotes': Path(args.notes).read_text(encoding='utf-8'), 'sourceCommit': args.source, 'unsigned': True}
    (output / 'update-ios.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: metadata[key] for key in ('versionName', 'versionCode', 'minSystemVersion', 'ipaSize', 'ipaSHA256', 'sourceCommit', 'unsigned')}, ensure_ascii=False))

if __name__ == '__main__': main()
