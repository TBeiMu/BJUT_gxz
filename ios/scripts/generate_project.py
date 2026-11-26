"""Generate the checked-in iOS-only project deterministically; no XcodeGen or signing tools."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'GongXiaoZhu'
PROJECT = ROOT / 'GongXiaoZhu.xcodeproj'

def identifier(label):
    return hashlib.sha256(label.encode()).hexdigest()[:24].upper()

def quoted(value):
    return json.dumps(str(value), ensure_ascii=False)

def generate():
    objects = []
    def obj(label, content):
        key = identifier(label)
        objects.append(f'\t\t{key} = {{ {content} }};')
        return key
    sources, resources, children, frameworks, products, packages = [], [], [], [], [], []
    files = sorted(p for p in SOURCE.rglob('*') if p.is_file() and not any(x.endswith('.xcassets') for x in p.relative_to(SOURCE).parts[:-1]))
    files += sorted(SOURCE.rglob('*.xcassets'))
    for path in files:
        relative = path.relative_to(SOURCE).as_posix()
        types = {'.swift': 'sourcecode.swift', '.c': 'sourcecode.c.c', '.h': 'sourcecode.c.h', '.plist': 'text.plist.xml', '.storyboard': 'file.storyboard', '.xcprivacy': 'text.xml', '.xcassets': 'folder.assetcatalog', '.txt': 'text'}
        reference = obj('file:' + relative, f'isa = PBXFileReference; lastKnownFileType = {quoted(types.get(path.suffix, "text"))}; path = {quoted(relative)}; sourceTree = "<group>";')
        children.append(reference)
        if path.suffix in ('.swift', '.c', '.storyboard', '.xcprivacy', '.xcassets', '.txt'):
            build = obj('build:' + relative, f'isa = PBXBuildFile; fileRef = {reference};')
            (sources if path.suffix in ('.swift', '.c') else resources).append(build)
    for name, version, product_names in [('SwiftSoup', '2.14.4', ['SwiftSoup']), ('swift-nio', '2.105.2', ['NIOCore', 'NIOHTTP1', 'NIOFoundationCompat']), ('swift-nio-transport-services', '1.28.0', ['NIOTransportServices'])]:
        owner = 'scinfu' if name == 'SwiftSoup' else 'apple'
        package = obj('package:' + name, f'isa = XCRemoteSwiftPackageReference; repositoryURL = {quoted(f"https://github.com/{owner}/{name}.git")}; requirement = {{ kind = exactVersion; version = {quoted(version)}; }};')
        packages.append(package)
        for product in product_names:
            product_id = obj('product:' + product, f'isa = XCSwiftPackageProductDependency; package = {package}; productName = {quoted(product)};')
            products.append(product_id)
            frameworks.append(obj('framework:' + product, f'isa = PBXBuildFile; productRef = {product_id};'))
    listfmt = lambda values: '(' + ', '.join(values) + (',' if values else '') + ')'
    source_group = obj('source-group', f'isa = PBXGroup; children = {listfmt(children)}; path = GongXiaoZhu; sourceTree = "<group>";')
    app = obj('app', 'isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = "工小助.app"; sourceTree = BUILT_PRODUCTS_DIR;')
    product_group = obj('product-group', f'isa = PBXGroup; children = ({app},); name = Products; sourceTree = "<group>";')
    root_group = obj('root-group', f'isa = PBXGroup; children = ({source_group}, {product_group},); sourceTree = "<group>";')
    source_phase = obj('sources', f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {listfmt(sources)}; runOnlyForDeploymentPostprocessing = 0;')
    resource_phase = obj('resources', f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {listfmt(resources)}; runOnlyForDeploymentPostprocessing = 0;')
    framework_phase = obj('frameworks', f'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = {listfmt(frameworks)}; runOnlyForDeploymentPostprocessing = 0;')
    gradle = (ROOT.parent / 'app/build.gradle.kts').read_text(encoding='utf-8')
    version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    build = re.search(r'versionCode\s*=\s*(\d+)', gradle).group(1)
    target_configs, project_configs = [], []
    common = {'ASSETCATALOG_COMPILER_APPICON_NAME': 'AppIcon', 'CLANG_ENABLE_MODULES': 'YES', 'CODE_SIGNING_ALLOWED': 'NO', 'CODE_SIGNING_REQUIRED': 'NO', 'CODE_SIGN_IDENTITY': '', 'CURRENT_PROJECT_VERSION': build, 'GENERATE_INFOPLIST_FILE': 'NO', 'INFOPLIST_FILE': 'GongXiaoZhu/Info.plist', 'IPHONEOS_DEPLOYMENT_TARGET': '17.0', 'LD_RUNPATH_SEARCH_PATHS': '$(inherited) @executable_path/Frameworks', 'MARKETING_VERSION': version, 'OTHER_LDFLAGS': '$(inherited) -lz', 'PRODUCT_BUNDLE_IDENTIFIER': 'io.github.tbeimu.gongxiaozhu.ios', 'PRODUCT_MODULE_NAME': 'GongXiaoZhu', 'PRODUCT_NAME': '工小助', 'SDKROOT': 'iphoneos', 'SUPPORTED_PLATFORMS': 'iphoneos iphonesimulator', 'SUPPORTS_MACCATALYST': 'NO', 'SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD': 'NO', 'SUPPORTS_XR_DESIGNED_FOR_IPHONE_IPAD': 'NO', 'SWIFT_OBJC_BRIDGING_HEADER': 'GongXiaoZhu/GongXiaoZhu-Bridging-Header.h', 'SWIFT_VERSION': '6.0', 'TARGETED_DEVICE_FAMILY': '1,2'}
    common['SWIFT_VERSION'] = '5.0'
    for config in ('Debug', 'Release'):
        settings = dict(common, SWIFT_OPTIMIZATION_LEVEL='-Onone' if config == 'Debug' else '-O', DEBUG_INFORMATION_FORMAT='dwarf' if config == 'Debug' else 'dwarf-with-dsym')
        if config == 'Debug': settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS'] = 'DEBUG'
        text = ' '.join(f'{key} = {quoted(value)};' for key, value in sorted(settings.items()))
        target_configs.append(obj('target-config:' + config, f'isa = XCBuildConfiguration; buildSettings = {{ {text} }}; name = {config};'))
        project_configs.append(obj('project-config:' + config, f'isa = XCBuildConfiguration; buildSettings = {{ CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SDKROOT = iphoneos; }}; name = {config};'))
    target_list = obj('target-configs', f'isa = XCConfigurationList; buildConfigurations = {listfmt(target_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
    project_list = obj('project-configs', f'isa = XCConfigurationList; buildConfigurations = {listfmt(project_configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
    target = obj('target', f'isa = PBXNativeTarget; buildConfigurationList = {target_list}; buildPhases = ({source_phase}, {framework_phase}, {resource_phase},); buildRules = (); dependencies = (); name = GongXiaoZhu; packageProductDependencies = {listfmt(products)}; productName = "工小助"; productReference = {app}; productType = "com.apple.product-type.application";')
    project = obj('project', f'isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 2630; }}; buildConfigurationList = {project_list}; compatibilityVersion = "Xcode 14.0"; developmentRegion = "zh-Hans"; hasScannedForEncodings = 0; knownRegions = ("zh-Hans", en, Base,); mainGroup = {root_group}; packageReferences = {listfmt(packages)}; productRefGroup = {product_group}; projectDirPath = ""; projectRoot = ""; targets = ({target},);')
    PROJECT.mkdir(parents=True, exist_ok=True)
    (PROJECT / 'project.pbxproj').write_text('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n' + '\n'.join(objects) + f'\n\t}};\n\trootObject = {project};\n}}\n', encoding='utf-8')
    scheme_dir = PROJECT / 'xcshareddata/xcschemes'
    scheme_dir.mkdir(parents=True, exist_ok=True)
    (scheme_dir / 'GongXiaoZhu.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2630" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="工小助.app" BlueprintName="GongXiaoZhu" ReferencedContainer="container:GongXiaoZhu.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="NO"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="工小助.app" BlueprintName="GongXiaoZhu" ReferencedContainer="container:GongXiaoZhu.xcodeproj"/></BuildableProductRunnable></LaunchAction>
  <ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''', encoding='utf-8')
    print(f'iOS project: {len(sources)} source files, {version}/{build}, no test targets')

if __name__ == '__main__': generate()
