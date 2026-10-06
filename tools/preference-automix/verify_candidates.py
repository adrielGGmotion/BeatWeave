#!/usr/bin/env python3
"""Compile production modules separately and run their model-free common tests."""
import json,os,re,subprocess
from pathlib import Path
from run_auto import ROOT,dependencies,save

def main():
    output=ROOT/'build/candidate-tests';output.mkdir(parents=True,exist_ok=True)
    config={'jvm_dependencies':json.loads((ROOT/'tools/verification-dependencies.json').read_text())['dependencies']}
    cp=os.pathsep.join(dependencies(config));deps=ROOT/'.cache/verify/deps'
    standard=[deps/'kotlin-stdlib-2.1.21.jar'];onnx=deps/'onnxruntime-1.22.0.jar'
    junit=[deps/n for n in ['kotlin-test-2.1.21.jar','kotlin-test-junit-2.1.21.jar','junit-4.13.2.jar','hamcrest-core-1.3.jar']]
    def source(module,sets):return sorted(p for s in sets for p in (ROOT/module/'src'/s).rglob('*.kt'))
    def compile(name,files,classpath,friend=None):
        jar=output/f'{name}.jar';cmd=['java','-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib','-no-reflect','-jvm-target','1.8','-module-name',name,'-classpath',os.pathsep.join(map(str,classpath)),'-d',str(jar)]
        if friend:cmd+=['-Xfriend-paths='+str(friend)]
        subprocess.run(cmd+list(map(str,files)),check=True);return jar
    core=compile('analysis',source('analysis',['commonMain','jvmMain']),standard)
    learned=compile('learned',source('learned-beats',['commonMain','ortMain','jvmMain']),standard+[core,onnx])
    tests=[];jars=[]
    for module,own,libs in [('analysis',core,[core]),('learned-beats',learned,[core,learned,onnx])]:
        files=source(module,['commonTest']);tests+=files
        jars.append(compile(module+'-tests',files,standard+libs+junit,own))
    classes=[]
    for p in tests:
        text=p.read_text()
        if '@Test' not in text:continue
        package=re.search(r'^package\s+([\w.]+)',text,re.M).group(1)
        classes.extend(package+'.'+c for c in re.findall(r'^(?:public\s+)?class\s+(\w+)',text,re.M))
    result=subprocess.run(['java','-Xmx2g','-cp',os.pathsep.join(map(str,standard+[core,learned,onnx]+junit+jars)),
        'org.junit.runner.JUnitCore',*sorted(set(classes))],capture_output=True,text=True)
    (output/'junit.txt').write_text(result.stdout+result.stderr)
    save(output/'result.json',{'passed':result.returncode==0,'classes':sorted(set(classes)),
        'scope':'Production analysis and learned-beats sources compiled at module boundaries; all common tests. No Android or native runtime tests.'})
    print(result.stdout+result.stderr,flush=True)
    if result.returncode:raise SystemExit(result.returncode)

if __name__=='__main__':main()
