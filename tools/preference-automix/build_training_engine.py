#!/usr/bin/env python3
"""Compile the unchanged planner with a training-only candidate observer.

The generated source adds one observation call in the ranked scan. It leaves
ranking, shortlists, candidate order, clock fits and acceptance limits intact.
"""
import hashlib,json,os,subprocess
from pathlib import Path
from run_auto import dependencies
ROOT=Path(__file__).resolve().parents[2]

def build(release):
    work=ROOT/'build/round5';work.mkdir(parents=True,exist_ok=True)
    config=json.loads((release/'config.json').read_text())
    cp=os.pathsep.join(dependencies(config))
    planner=ROOT/'learned-beats/src/commonMain/kotlin/org/metrolist/beatweave/learned/AutoMixPlanner.kt'
    source=planner.read_text()
    needle='                candidates +=\n                    RankedTransition('
    assert source.count(needle)==1
    observed=source.replace(needle,'                CandidateRecorder.record(exit.part, entry.part, measured, bars,\n                    first.audio.durationSeconds, second.audio.durationSeconds, evidence.score)\n'+needle)
    generated=work/'AutoMixPlanner.kt';generated.write_text(observed)
    # Preserve the existing inference/export contract, extending the plan mode
    # with an optional observer file controlled only by a JVM property.
    entry=ROOT/'tools/preference-automix/PreferenceEngine.kt'
    engine=entry.read_text().replace('    try {\n        val accepted=',
        '    val recording=System.getProperty("beatweave.candidateFile")?.let{File(it).printWriter()}\n'
        '    CandidateRecorder.writer=recording\n'
        '    recording?.println("a,b,duration,speed,bars,baseline,old_score,"+(0..12).joinToString(","){"x$it"})\n'
        '    try {\n        val accepted=')
    engine=engine.replace('        println("DECLINE ${e.report.failure}")\n    }',
        '        println("DECLINE ${e.report.failure}")\n    } finally { recording?.close(); CandidateRecorder.writer=null }')
    engine=engine.replace('    val rows=File(args[3])',
        '    System.getProperty("beatweave.referenceFile")?.let {\n'
        '        CandidateRecorder.reference(songs[0],songs[1],File(it),File(output,"approved-features.csv"))\n'
        '    }\n    val rows=File(args[3])')
    engine=engine.replace('"beatweave-manual-pilot-v1")',
        'System.getProperty("beatweave.cueModelId","beatweave-manual-pilot-v1"))')
    main=work/'PreferenceEngine.kt';main.write_text(engine)
    sources=[]
    for folder in ['analysis/src/commonMain','learned-beats/src/commonMain','learned-beats/src/ortMain']:
        sources.extend(p for p in (ROOT/folder).rglob('*.kt') if p!=planner)
    sources.extend([generated,main,ROOT/'tools/preference-automix/CandidateRecorder.kt'])
    jar=work/'training-engine.jar'
    subprocess.run(['java','-Xmx2g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',cp,'-d',str(jar),*map(str,sources)],check=True)
    (work/'classpath.txt').write_text(str(jar)+os.pathsep+cp)
    (work/'instrumentation.json').write_text(json.dumps({'planner_sha256':hashlib.sha256(planner.read_bytes()).hexdigest(),
        'observed_planner_sha256':hashlib.sha256(generated.read_bytes()).hexdigest(),
        'changes':'One candidate-recording call; otherwise original planner source unchanged',
        'candidate_scope':'Production supported bar/pulse shortlists and meter-compatible pairs; final clock fitting is done only when a candidate is selected.'},indent=2)+'\n')
    print(jar,flush=True)

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('release',type=Path);a=p.parse_args();build(a.release)
