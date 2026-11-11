#!/usr/bin/env python3
"""Bounded app/frame/media smoke test; never certify physical output or erase an OS."""
import argparse
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--tag',default='gabos-experience')
    args=parser.parse_args()
    if not args.tag.replace('-','').replace('_','').isalnum():raise SystemExit('Unsafe tag')
    prefix=[shutil.which('adb'),'-s','0123456789ABCDEF']
    def shell(*parts,timeout=25):
        result=subprocess.run(prefix+['shell',shlex.join(parts)],capture_output=True,text=True,encoding='utf-8',timeout=timeout)
        return result.stdout.strip()
    if (shell('getprop','ro.gabos.name')!='GabOS' or shell('getprop','sys.boot_completed')!='1'
        or shell('getprop','ro.build.version.sdk')!='31' or not any(
            line.split()[:2]==['/dev/block/loop6','/data'] for line in shell('cat','/proc/mounts').splitlines())):
        raise SystemExit('Requires completed isolated GabOS 12')
    if not args.execute:
        print('Plan: frame callbacks, local app/native Files navigation, optional OMX enumeration and bounded sample playback.');return
    result={'physical_projection_checked':False,'physical_audio_checked':False,'apps':{}}
    before={name:shell('pidof',name) for name in ('system_server','netd')}
    shell('am','force-stop','local.gabos.diagnostics')
    shell('rm','-f','/data/data/local.gabos.diagnostics/files/radio-result.json')
    shell('am','start','-n','local.gabos.diagnostics/.MainActivity','--es','test','frames')
    time.sleep(23)
    raw=shell('cat','/data/data/local.gabos.diagnostics/files/radio-result.json')
    try:result['frame_probe']=json.loads(raw)
    except ValueError:result['frame_probe']={'error':'No readable frame result'}
    print('Frame probe:',result['frame_probe'].get('frame_callbacks_5s','not completed'),flush=True)
    for package,component in [('com.android.tv.settings','.MainSettings'),('local.gabos.media','.MainActivity'),('com.android.documentsui','.LauncherActivity')]:
        launch=shell('am','start','-W','-n',package+'/'+component)
        time.sleep(1)
        activity=shell('dumpsys','activity','activities')
        focus=[line.strip() for line in activity.splitlines() if 'mResumedActivity:' in line]
        result['apps'][package]={'launch':launch,'resumed':focus,'expected_resumed':any(package+'/' in line for line in focus)}
        shell('input','keyevent','4')
        print(package,result['apps'][package]['expected_resumed'],flush=True)
    if shell('getprop','ro.rayo.media.legacy_omx')=='true':
        probe=subprocess.run(prefix+['shell','/system/bin/rayo12_omx_plugin_probe'],capture_output=True,text=True,timeout=25)
        result['factory_omx_enumeration']={'exit_code':probe.returncode,'stdout':probe.stdout,'stderr':probe.stderr}
        print(result['factory_omx_enumeration'],flush=True)
    shell('am','force-stop','local.rayo.validation')
    result['media_launch']=shell('am','start','-n','local.rayo.validation/.MainActivity','--es','test','video')
    time.sleep(30)
    pid=shell('pidof','local.rayo.validation')
    if pid.isdigit():
        result['media_log']=shell('logcat','-d','--pid='+pid,'-s','RayoValidation:V','*:S')
    result['player_dump']=shell('dumpsys','media.player')
    shell('am','start','-a','android.settings.SETTINGS')
    result['core_before']=before
    result['core_after']={name:shell('pidof',name) for name in before}
    result['core_unchanged']=all(before[name] and before[name]==result['core_after'][name] for name in before)
    path=ROOT/'local/google-tv-reference'/(args.tag+'.json')
    path.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('Saved',path,'core unchanged:',result['core_unchanged'],flush=True)

if __name__=='__main__':main()
