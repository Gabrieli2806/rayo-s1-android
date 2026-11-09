#!/usr/bin/env python3
"""Generate an original, bounded AVC/AAC test clip; no external sample media."""
from pathlib import Path
import argparse
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--ffmpeg', default=shutil.which('ffmpeg'))
args = parser.parse_args()
encoder = args.ffmpeg
if not encoder:
    try:
        import imageio_ffmpeg
        encoder = imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        parser.error('Supply --ffmpeg or install imageio-ffmpeg')
args.output.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([encoder, '-hide_banner', '-loglevel', 'error', '-y',
                '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24',
                '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100',
                '-t', '6', '-c:v', 'libx264', '-profile:v', 'baseline',
                '-pix_fmt', 'yuv420p', '-b:v', '400k', '-c:a', 'aac',
                '-b:a', '64k', '-movflags', '+faststart', str(args.output)], check=True)
print('Generated original 6-second AVC/AAC diagnostic clip:', args.output)
